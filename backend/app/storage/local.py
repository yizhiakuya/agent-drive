"""本地文件系统存储实现（Storage 协议）。

路径安全：resolve() 防穿越 + 组件级拒绝符号链接；写操作原子（tmp + replace）。
可选挂载 UploadIndex：所有内容变更自动失效秒传索引条目。"""
from __future__ import annotations

import ctypes
import errno
import json
import mimetypes
import os
import secrets
import shutil
import stat
import sys
import tempfile
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any, BinaryIO

try:  # Linux 生产环境用进程间锁；不支持 fcntl 的平台仍保留进程内 RLock。
    import fcntl
except ImportError:  # pragma: no cover - Windows compatibility
    fcntl = None  # type: ignore[assignment]


class LocalStorage:
    _INTERNAL_NAMES = frozenset({".index", ".trash", ".storage.lock"})
    _INTERNAL_PREFIXES = (".upload.", ".copy.", ".copy-old.")

    def __init__(self, root: Path | str):
        self.root = Path(root).resolve()  # 规范化根路径（防根路径含符号链接绕过）
        self.root.mkdir(parents=True, exist_ok=True)
        self.index: Any | None = None  # UploadIndex 可选挂载（container 组装后注入）
        self._change_listeners: list[Callable[[str, list[str]], None]] = []
        self._mutation_lock = threading.RLock()
        self._mutation_state = threading.local()
        self._lock_path = self.root / ".storage.lock"
        fd = os.open(
            self._lock_path,
            os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        try:
            os.fchmod(fd, 0o600)
        finally:
            os.close(fd)
        with self._locked_mutation():
            self._cleanup_copy_staging()

    def attach_index(self, index: Any) -> None:
        """挂载秒传索引：此后所有内容变更自动失效对应条目。"""
        self.index = index

    def attach_change_listener(self, listener: Callable[[str, list[str]], None]) -> None:
        """Register a best-effort observer for index/task lifecycle updates."""
        self._change_listeners.append(listener)

    def _notify_change(self, event: str, *paths: str) -> None:
        for listener in self._change_listeners:
            try:
                listener(event, list(paths))
            except Exception:
                pass

    def _index_forget(self, rel_path: str, *, recursive: bool = False) -> None:
        """内容将被改变/移走：失效其秒传条目（索引失败不阻塞主流程）。"""
        if self.index is not None:
            try:
                self.index.forget_path(rel_path, recursive=recursive)
            except TypeError:  # 兼容测试替身/旧式索引实现。
                try:
                    self.index.forget_path(rel_path)
                except Exception:
                    pass
            except Exception:
                pass

    @contextmanager
    def _locked_mutation(self) -> Iterator[None]:
        """进程内 RLock + 进程间 flock，保护 read-copy-publish 等复合写操作。"""
        with self._mutation_lock:
            depth = getattr(self._mutation_state, "depth", 0)
            self._mutation_state.depth = depth + 1
            lock_fd: int | None = None
            try:
                if depth == 0 and fcntl is not None:
                    lock_fd = os.open(self._lock_path, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
                    fcntl.flock(lock_fd, fcntl.LOCK_EX)
                yield
            finally:
                self._mutation_state.depth = depth
                if lock_fd is not None:
                    fcntl.flock(lock_fd, fcntl.LOCK_UN)
                    os.close(lock_fd)

    @classmethod
    def _is_internal_name(cls, name: str) -> bool:
        return name in cls._INTERNAL_NAMES or name.startswith(cls._INTERNAL_PREFIXES)

    def _write_copy_transaction(self, stage: str, backup: str, destination: str) -> str:
        """在移动旧目标前 durable 写入 recovery marker。"""
        marker = f".copy.{secrets.token_hex(12)}.txn.json"
        payload = json.dumps(
            {"stage": stage, "backup": backup, "destination": destination},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        fd, temp_name = tempfile.mkstemp(prefix=".copy.", suffix=".txn-write.tmp", dir=self.root)
        temp = Path(temp_name)
        root_fd: int | None = None
        try:
            with os.fdopen(fd, "wb") as stream:
                fd = -1
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temp, 0o600)
            root_fd = os.open(self.root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            os.link(temp.name, marker, src_dir_fd=root_fd, dst_dir_fd=root_fd, follow_symlinks=False)
            os.unlink(temp.name, dir_fd=root_fd)
            os.fsync(root_fd)
            return marker
        except Exception:
            if fd >= 0:
                os.close(fd)
            temp.unlink(missing_ok=True)
            raise
        finally:
            if root_fd is not None:
                os.close(root_fd)

    def _recover_copy_transaction(self, marker: str) -> bool:
        """按 transaction marker 恢复崩溃中的目录交换；无法判定时保守保留。"""
        marker_path = self.root / marker
        marker_fd: int | None = None
        try:
            marker_fd = os.open(marker_path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(marker_fd, "r", encoding="utf-8") as stream:
                marker_fd = None
                data = json.load(stream)
            stage = str(data["stage"])
            backup = str(data["backup"])
            destination = str(data["destination"])
            if any("/" in value or value in {"", ".", ".."} for value in (stage, backup)):
                raise ValueError("非法复制事务名称")
            if not stage.startswith(".copy.") or not backup.startswith(".copy-old."):
                raise ValueError("非法复制事务命名空间")
            destination_path = self.resolve(destination)
            destination_rel = destination_path.relative_to(self.root).as_posix()
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError, UnicodeDecodeError):
            return False
        finally:
            if marker_fd is not None:
                os.close(marker_fd)

        root_fd = os.open(self.root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        destination_fd: int | None = None
        try:
            destination_fd, destination_leaf = self._open_parent_fd(destination_rel, create=False)
            def present(name: str) -> bool:
                try:
                    self._lstat_at(root_fd, name)
                    return True
                except FileNotFoundError:
                    return False

            has_stage = present(stage)
            has_backup = present(backup)
            try:
                self._lstat_at(destination_fd, destination_leaf)
                has_destination = True
            except FileNotFoundError:
                has_destination = False

            resolved = False
            if has_backup and not has_destination:
                # 崩溃发生在 old→backup 之后、new→destination 之前：恢复旧目标。
                os.rename(backup, destination_leaf, src_dir_fd=root_fd, dst_dir_fd=destination_fd)
                resolved = True
            elif has_destination and not has_stage:
                # 新目标已经提交；旧 backup 只是待清理残留。
                resolved = True
            elif not has_backup and has_destination:
                # backup 尚未创建或已清理；公共目标仍在，stage 不再有公共用途。
                resolved = True
            if not resolved:
                return False

            # 先持久化恢复/提交后的公共目录项，再清理 recovery 信息。
            os.fsync(destination_fd)
            os.fsync(root_fd)
            if has_stage:
                self._delete_rel_at(stage, missing_ok=True)
            if has_backup and has_destination:
                self._delete_rel_at(backup, missing_ok=True)
            self._delete_rel_at(marker, missing_ok=True)
            return True
        except (OSError, PermissionError, ValueError):
            return False
        finally:
            if destination_fd is not None:
                os.close(destination_fd)
            os.close(root_fd)

    def _cleanup_copy_staging(self) -> None:
        """清理崩溃遗留 staging；无 marker 的 `.copy-old` 永不自动删除以免丢旧数据。"""
        directory_flag = getattr(os, "O_DIRECTORY", 0)
        nofollow_flag = getattr(os, "O_NOFOLLOW", 0)
        root_fd = os.open(self.root, os.O_RDONLY | directory_flag | nofollow_flag)
        changed = False
        try:
            with os.scandir(root_fd) as entries:
                names = [entry.name for entry in entries]
            for name in names:
                if name.startswith(".copy.") and name.endswith(".txn.json"):
                    self._recover_copy_transaction(name)
            with os.scandir(root_fd) as entries:
                remaining_markers = any(
                    entry.name.startswith(".copy.") and entry.name.endswith(".txn.json")
                    for entry in entries
                )
            # 任一 marker 尚未恢复时，所有 staging 都保守保留；不能猜测归属后误删。
            if remaining_markers:
                return
            with os.scandir(root_fd) as entries:
                for entry in entries:
                    name = entry.name
                    if not name.startswith(".copy.") or name.endswith(".txn.json"):
                        continue
                    try:
                        value = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
                        if stat.S_ISDIR(value.st_mode):
                            self._remove_tree_at(root_fd, name)
                        else:
                            os.unlink(name, dir_fd=root_fd)
                        changed = True
                    except FileNotFoundError:
                        continue
                    except OSError:
                        # 遗留项可能属于不可读挂载点；保留它，后续启动继续尝试。
                        continue
            if changed:
                os.fsync(root_fd)
        finally:
            os.close(root_fd)

    def _open_parent_fd(
        self,
        rel_path: str,
        *,
        create: bool = True,
        allow_internal: bool = False,
    ) -> tuple[int, str]:
        """用 openat/O_NOFOLLOW 打开父目录，避免校验后父组件被换成 symlink。"""
        relative = Path(rel_path)
        parts = [part for part in relative.parts if part not in ("", ".")]
        if relative.is_absolute() or ".." in parts or not parts:
            raise PermissionError(f"路径越界: {rel_path}")
        if not allow_internal and self._is_internal_name(parts[0]):
            raise PermissionError("内部存储路径不可访问")
        directory_flag = getattr(os, "O_DIRECTORY", 0)
        nofollow_flag = getattr(os, "O_NOFOLLOW", 0)
        current = os.open(self.root, os.O_RDONLY | directory_flag)
        try:
            for part in parts[:-1]:
                try:
                    child = os.open(
                        part,
                        os.O_RDONLY | directory_flag | nofollow_flag,
                        dir_fd=current,
                    )
                except FileNotFoundError:
                    if not create:
                        raise
                    try:
                        os.mkdir(part, mode=0o700, dir_fd=current)
                    except FileExistsError:
                        pass
                    child = os.open(
                        part,
                        os.O_RDONLY | directory_flag | nofollow_flag,
                        dir_fd=current,
                    )
                except OSError as exc:
                    if exc.errno in (errno.ELOOP, errno.ENOTDIR):
                        raise PermissionError(f"路径越界(符号链接): {rel_path}") from exc
                    raise
                os.close(current)
                current = child
            return current, parts[-1]
        except Exception:
            os.close(current)
            raise

    @staticmethod
    def _lstat_at(parent_fd: int, leaf: str) -> os.stat_result:
        value = os.stat(leaf, dir_fd=parent_fd, follow_symlinks=False)
        if stat.S_ISLNK(value.st_mode):
            raise PermissionError("路径越界(符号链接)")
        return value

    @staticmethod
    def _renameat2(
        src_fd: int,
        src_leaf: str,
        dst_fd: int,
        dst_leaf: str,
        flags: int,
    ) -> bool:
        """调用 Linux renameat2；内核不支持时返回 False，其他错误原样抛出。"""
        try:
            function = ctypes.CDLL(None, use_errno=True).renameat2
        except AttributeError:
            return False
        function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        function.restype = ctypes.c_int
        result = function(
            src_fd,
            os.fsencode(src_leaf),
            dst_fd,
            os.fsencode(dst_leaf),
            flags,
        )
        if result == 0:
            return True
        error = ctypes.get_errno()
        if error in (errno.ENOSYS, errno.EINVAL, getattr(errno, "ENOTSUP", errno.EINVAL)):
            return False
        raise OSError(error, os.strerror(error), dst_leaf)

    def _secure_rename(
        self,
        src: str,
        dst: str,
        *,
        overwrite: bool = True,
        create_destination_parent: bool = False,
    ) -> tuple[str, bool, os.stat_result]:
        """在已持有 mutation lock 时以 dirfd 相对路径移动一个叶节点。"""
        source = self.resolve(src, allow_internal=True)
        target = self.resolve(dst, allow_internal=True)
        source_rel = source.relative_to(self.root).as_posix()
        target_rel = target.relative_to(self.root).as_posix()
        if source_rel == target_rel:
            raise ValueError(f"源与目标相同: {src}")
        src_fd: int | None = None
        dst_fd: int | None = None
        src_leaf = ""
        dst_leaf = ""
        try:
            src_fd, src_leaf = self._open_parent_fd(
                source_rel, create=False, allow_internal=True,
            )
            dst_fd, dst_leaf = self._open_parent_fd(
                target_rel,
                create=create_destination_parent,
                allow_internal=True,
            )
            self._lstat_at(src_fd, src_leaf)
            try:
                dst_stat = self._lstat_at(dst_fd, dst_leaf)
                dst_existed = True
            except FileNotFoundError:
                dst_stat = None
                dst_existed = False
            if dst_existed and not overwrite:
                raise FileExistsError(f"目标已存在: {target_rel}")
            if overwrite:
                src_stat = self._lstat_at(src_fd, src_leaf)
                src_is_dir = stat.S_ISDIR(src_stat.st_mode)
                if dst_existed:
                    assert dst_stat is not None
                    dst_is_dir = stat.S_ISDIR(dst_stat.st_mode)
                    if src_is_dir != dst_is_dir:
                        if src_is_dir:
                            raise NotADirectoryError(f"目录不能覆盖文件: {target_rel}")
                        raise IsADirectoryError(f"文件不能覆盖目录: {target_rel}")
                try:
                    os.replace(src_leaf, dst_leaf, src_dir_fd=src_fd, dst_dir_fd=dst_fd)
                except OSError as exc:
                    if exc.errno in (errno.ENOTEMPTY, errno.EEXIST):
                        raise FileExistsError(f"目标目录非空: {target_rel}") from exc
                    raise
            else:
                # For files, link+unlink gives an atomic no-clobber publication.
                src_stat = self._lstat_at(src_fd, src_leaf)
                if stat.S_ISDIR(src_stat.st_mode):
                    if not self._renameat2(src_fd, src_leaf, dst_fd, dst_leaf, 1):
                        # 非 Linux fallback：项目写操作仍由全局 mutation lock 串行化。
                        try:
                            self._lstat_at(dst_fd, dst_leaf)
                        except FileNotFoundError:
                            os.rename(src_leaf, dst_leaf, src_dir_fd=src_fd, dst_dir_fd=dst_fd)
                        else:
                            raise FileExistsError(f"目标已存在: {target_rel}")
                else:
                    os.link(
                        src_leaf,
                        dst_leaf,
                        src_dir_fd=src_fd,
                        dst_dir_fd=dst_fd,
                        follow_symlinks=False,
                    )
                    try:
                        os.unlink(src_leaf, dir_fd=src_fd)
                    except Exception:
                        os.unlink(dst_leaf, dir_fd=dst_fd)
                        raise
            moved_stat = self._lstat_at(dst_fd, dst_leaf)
            os.fsync(src_fd)
            if dst_fd != src_fd:
                os.fsync(dst_fd)
            return target_rel, dst_existed, moved_stat
        finally:
            if src_fd is not None:
                os.close(src_fd)
            if dst_fd is not None:
                os.close(dst_fd)

    def _remove_tree_at(self, parent_fd: int, leaf: str) -> None:
        """递归删除目录，所有子项均以 nofollow fd 操作。"""
        directory_flag = getattr(os, "O_DIRECTORY", 0)
        nofollow_flag = getattr(os, "O_NOFOLLOW", 0)
        directory_fd = os.open(
            leaf,
            os.O_RDONLY | directory_flag | nofollow_flag,
            dir_fd=parent_fd,
        )
        try:
            with os.scandir(directory_fd) as entries:
                for entry in entries:
                    child = entry.name
                    child_stat = os.stat(child, dir_fd=directory_fd, follow_symlinks=False)
                    if stat.S_ISLNK(child_stat.st_mode):
                        # 删除链接本身而非跟随，既不越界也不让回滚/清理被恶意残留阻断。
                        os.unlink(child, dir_fd=directory_fd)
                    elif stat.S_ISDIR(child_stat.st_mode):
                        self._remove_tree_at(directory_fd, child)
                    else:
                        os.unlink(child, dir_fd=directory_fd)
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        os.rmdir(leaf, dir_fd=parent_fd)

    def _delete_rel_at(self, rel_path: str, *, missing_ok: bool = False) -> bool:
        """在已持有 mutation lock 时安全删除一个文件或目录树。"""
        parent_fd, leaf = self._open_parent_fd(
            rel_path, create=False, allow_internal=True,
        )
        try:
            try:
                value = self._lstat_at(parent_fd, leaf)
            except FileNotFoundError:
                if missing_ok:
                    return False
                raise
            if stat.S_ISDIR(value.st_mode):
                self._remove_tree_at(parent_fd, leaf)
            else:
                os.unlink(leaf, dir_fd=parent_fd)
            os.fsync(parent_fd)
            return True
        finally:
            os.close(parent_fd)

    def _copy_file_at(self, source_fd: int, leaf: str, dst: str, *, overwrite: bool) -> None:
        nofollow_flag = getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(leaf, os.O_RDONLY | nofollow_flag, dir_fd=source_fd)
        try:
            value = os.fstat(fd)
            if not stat.S_ISREG(value.st_mode):
                raise ValueError(f"不支持复制特殊文件: {leaf}")
            tmp, output = self.create_temp_file()
            try:
                with os.fdopen(os.dup(fd), "rb") as source, output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
                info = self.publish_temp(dst, tmp, exclusive=not overwrite)
                info.pop("_revision", None)
            finally:
                tmp.unlink(missing_ok=True)
        finally:
            os.close(fd)

    def _copy_tree_contents(self, source_fd: int, destination_fd: int) -> None:
        """把已打开源目录完整复制到空 staging 目录，不发布任何中间状态。"""
        directory_flag = getattr(os, "O_DIRECTORY", 0)
        nofollow_flag = getattr(os, "O_NOFOLLOW", 0)
        with os.scandir(source_fd) as entries:
            for entry in entries:
                child_stat = os.stat(entry.name, dir_fd=source_fd, follow_symlinks=False)
                if stat.S_ISLNK(child_stat.st_mode):
                    raise PermissionError("目录树中包含符号链接")
                if stat.S_ISDIR(child_stat.st_mode):
                    os.mkdir(entry.name, mode=0o700, dir_fd=destination_fd)
                    source_child: int | None = None
                    destination_child: int | None = None
                    try:
                        source_child = os.open(
                            entry.name,
                            os.O_RDONLY | directory_flag | nofollow_flag,
                            dir_fd=source_fd,
                        )
                        destination_child = os.open(
                            entry.name,
                            os.O_RDONLY | directory_flag | nofollow_flag,
                            dir_fd=destination_fd,
                        )
                        self._copy_tree_contents(source_child, destination_child)
                        os.fsync(destination_child)
                    finally:
                        if source_child is not None:
                            os.close(source_child)
                        if destination_child is not None:
                            os.close(destination_child)
                elif stat.S_ISREG(child_stat.st_mode):
                    source_file: int | None = None
                    destination_file: int | None = None
                    try:
                        source_file = os.open(
                            entry.name,
                            os.O_RDONLY | nofollow_flag,
                            dir_fd=source_fd,
                        )
                        destination_file = os.open(
                            entry.name,
                            os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow_flag,
                            0o600,
                            dir_fd=destination_fd,
                        )
                        with os.fdopen(os.dup(source_file), "rb") as source_stream, os.fdopen(
                            os.dup(destination_file), "wb",
                        ) as destination_stream:
                            shutil.copyfileobj(source_stream, destination_stream, length=1024 * 1024)
                            destination_stream.flush()
                            os.fsync(destination_stream.fileno())
                    finally:
                        if source_file is not None:
                            os.close(source_file)
                        if destination_file is not None:
                            os.close(destination_file)
                else:
                    raise ValueError(f"不支持复制特殊文件: {entry.name}")

    def _publish_staged_directory(self, stage: str, dst: str, *, overwrite: bool) -> bool:
        """一次发布完整 staging 目录；返回值表示目标是否已经提交。"""
        root_fd = os.open(self.root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        destination_fd: int | None = None
        try:
            destination_fd, destination_leaf = self._open_parent_fd(dst, create=True)
            try:
                destination_stat = self._lstat_at(destination_fd, destination_leaf)
            except FileNotFoundError:
                destination_stat = None
            committed = False
            if destination_stat is None:
                if not self._renameat2(root_fd, stage, destination_fd, destination_leaf, 1):
                    # 旧平台只能依赖 mutation lock 串行化；不使用 replace，避免覆盖
                    # 在检查与发布之间被其他协作写者创建的目标。
                    try:
                        self._lstat_at(destination_fd, destination_leaf)
                    except FileNotFoundError:
                        os.rename(stage, destination_leaf, src_dir_fd=root_fd, dst_dir_fd=destination_fd)
                    else:
                        raise FileExistsError(f"目标已存在: {dst}")
                committed = True
            elif not overwrite:
                raise FileExistsError(f"目标已存在: {dst}（需 overwrite=true）")
            elif not stat.S_ISDIR(destination_stat.st_mode):
                raise NotADirectoryError(dst)
            elif not self._renameat2(root_fd, stage, destination_fd, destination_leaf, 2):
                # fallback 的关键是：stage→destination 成功后即视为已提交；
                # 后续清理失败只能留下隐藏 backup，不能向上层报告“复制失败”。
                backup = f".copy-old.{secrets.token_hex(12)}.tmp"
                marker = self._write_copy_transaction(stage, backup, dst)
                transaction_resolved = False
                try:
                    os.rename(destination_leaf, backup, src_dir_fd=destination_fd, dst_dir_fd=root_fd)
                    try:
                        os.rename(stage, destination_leaf, src_dir_fd=root_fd, dst_dir_fd=destination_fd)
                    except Exception:
                        try:
                            os.rename(backup, destination_leaf, src_dir_fd=root_fd, dst_dir_fd=destination_fd)
                            transaction_resolved = True
                        except OSError as restore_error:
                            raise RuntimeError("目录复制失败且旧目录回滚失败") from restore_error
                        raise
                    committed = True
                    try:
                        # 新目录项 durable 后才允许删除旧 backup；fsync 失败则保留
                        # marker + backup，避免掉电后新旧两份都不可恢复。
                        os.fsync(destination_fd)
                        os.fsync(root_fd)
                        self._remove_tree_at(root_fd, backup)
                        os.fsync(root_fd)
                        transaction_resolved = True
                    except OSError:
                        # marker + backup 留给下次持锁初始化清理。
                        pass
                finally:
                    if transaction_resolved:
                        try:
                            self._delete_rel_at(marker, missing_ok=True)
                        except OSError:
                            pass
            else:
                # RENAME_EXCHANGE 后 stage 名称指向旧目标。清理失败时保留
                # 隐藏 stage，由 _cleanup_copy_staging 在后续 mutation 中回收。
                committed = True
                try:
                    self._remove_tree_at(root_fd, stage)
                except OSError:
                    pass

            # rename/exchange 已经完成后，fsync 失败不能把一个已发布目标伪报为失败；
            # 可见内容保持新版本，后续 mutation 会再次 fsync 目录。
            try:
                os.fsync(destination_fd)
                os.fsync(root_fd)
            except OSError:
                if not committed:
                    raise
            return committed
        finally:
            if destination_fd is not None:
                os.close(destination_fd)
            os.close(root_fd)

    def _copy_directory_at(self, source_fd: int, leaf: str, dst: str, *, overwrite: bool) -> None:
        directory_flag = getattr(os, "O_DIRECTORY", 0)
        nofollow_flag = getattr(os, "O_NOFOLLOW", 0)
        source_directory = os.open(
            leaf,
            os.O_RDONLY | directory_flag | nofollow_flag,
            dir_fd=source_fd,
        )
        stage = f".copy.{secrets.token_hex(12)}.tmp"
        root_fd = os.open(self.root, os.O_RDONLY | directory_flag)
        published = False
        try:
            os.mkdir(stage, mode=0o700, dir_fd=root_fd)
            stage_fd = os.open(
                stage,
                os.O_RDONLY | directory_flag | nofollow_flag,
                dir_fd=root_fd,
            )
            try:
                self._copy_tree_contents(source_directory, stage_fd)
                os.fsync(stage_fd)
            finally:
                os.close(stage_fd)
            self._publish_staged_directory(stage, dst, overwrite=overwrite)
            published = True
        finally:
            os.close(source_directory)
            os.close(root_fd)
            try:
                self._delete_rel_at(stage, missing_ok=True)
            except OSError:
                if not published:
                    raise

    # ---------- 路径安全 ----------
    def resolve(self, rel_path: str, *, allow_internal: bool = False) -> Path:
        """把相对路径安全解析为绝对路径，防穿越 + 拒绝符号链接。

        业务从不产生符号链接：任何出现在数据树内的链接（指向 system/、
        网盘外等）一律拒绝，堵死 symlink 逃逸读取敏感文件。
        """
        relative = Path(rel_path)
        if relative.is_absolute() or ".." in relative.parts:
            raise PermissionError(f"路径越界: {rel_path}")
        if (
            not allow_internal
            and relative.parts
            and self._is_internal_name(relative.parts[0])
        ):
            raise PermissionError("内部存储路径不可访问")
        node = self.root
        for part in relative.parts:
            if part in ("", "."):
                continue
            node = node / part
            # 必须在 resolve 之前逐组件检查；resolve 会跟随链接并抹掉证据。
            if node.is_symlink():
                raise PermissionError(f"路径越界(符号链接): {rel_path}")
        try:
            resolved = node.resolve()
            resolved.relative_to(self.root)
        except (OSError, RuntimeError, ValueError) as exc:
            raise PermissionError(f"路径越界: {rel_path}") from exc
        return resolved

    # ---------- 文件操作 ----------
    def list_dir(self, rel_path: str = "") -> list[dict[str, Any]]:
        d = self.resolve(rel_path)
        if not d.is_dir():
            raise NotADirectoryError(rel_path)
        items: list[dict[str, Any]] = []
        with os.scandir(d) as it:
            entries = sorted(it, key=lambda entry: entry.name.lower())
            for e in entries:
                if self._is_internal_name(e.name):
                    continue  # 隐藏索引/回收站/在途写入临时文件
                st = e.stat(follow_symlinks=False)
                if stat.S_ISLNK(st.st_mode):
                    raise PermissionError(f"目录包含符号链接: {e.name}")
                is_file = stat.S_ISREG(st.st_mode)
                if not is_file and not stat.S_ISDIR(st.st_mode):
                    raise PermissionError(f"目录包含不支持的特殊文件: {e.name}")
                rel = Path(e.path).relative_to(self.root).as_posix()
                items.append({
                    "name": e.name,
                    "path": rel,
                    "is_dir": not is_file,
                    "size": st.st_size if is_file else 0,
                    "mtime": st.st_mtime,
                })
        items.sort(key=lambda item: (not item["is_dir"], item["name"].lower()))
        return items

    def read_text(self, rel_path: str, max_chars: int = 8000) -> str:
        p = self.resolve(rel_path)
        if not p.is_file():
            raise FileNotFoundError(rel_path)
        raw = p.read_bytes()
        for enc in ("utf-8", "gbk", "latin-1"):
            try:
                text = raw.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        else:
            text = f"(二进制文件，无法以文本读取: {p.name})"
        return text[:max_chars]

    def create_temp_file(self) -> tuple[Path, BinaryIO]:
        """在数据根目录内创建 0600 临时文件，供请求流式写入后原子发布。"""
        fd, name = tempfile.mkstemp(prefix=".upload.", suffix=".tmp", dir=self.root)
        try:
            os.chmod(name, 0o600)
            return Path(name), os.fdopen(fd, "wb")
        except Exception:
            os.close(fd)
            Path(name).unlink(missing_ok=True)
            raise

    def publish_temp(
        self,
        rel_path: str,
        temp_path: Path | str,
        exclusive: bool = False,
        *,
        _allow_internal: bool = False,
    ) -> dict[str, Any]:
        """将 create_temp_file() 产生的文件原子发布到目标路径。

        exclusive=True 使用硬链接保证目标在并发下绝不被覆盖；父目录通过 dirfd +
        O_NOFOLLOW 逐级打开，避免校验后被替换成符号链接的竞态。独占冲突时保留
        临时文件供调用方换名重试；link/replace 是提交点，提交后的 fsync/清理失败
        不再伪报发布失败。
        """
        target = self.resolve(rel_path, allow_internal=_allow_internal)
        original_tmp = Path(temp_path)
        keep_for_retry = False
        committed = False
        parent_fd: int | None = None
        root_fd: int | None = None
        try:
            tmp = original_tmp.resolve(strict=True)
            tmp_stat = original_tmp.lstat()
            if tmp.parent != self.root or stat.S_ISLNK(tmp_stat.st_mode) or not stat.S_ISREG(tmp_stat.st_mode):
                raise PermissionError("临时文件不属于当前存储")
            size = tmp_stat.st_size
            with self._locked_mutation():
                parent_fd, leaf = self._open_parent_fd(
                    rel_path, allow_internal=_allow_internal,
                )
                try:
                    self._lstat_at(parent_fd, leaf)
                    existed = True
                except FileNotFoundError:
                    existed = False
                root_fd = os.open(self.root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
                try:
                    if exclusive:
                        os.link(
                            original_tmp.name,
                            leaf,
                            src_dir_fd=root_fd,
                            dst_dir_fd=parent_fd,
                            follow_symlinks=False,
                        )
                    else:
                        os.replace(original_tmp.name, leaf, src_dir_fd=root_fd, dst_dir_fd=parent_fd)
                except FileExistsError:
                    keep_for_retry = True
                    raise FileExistsError(rel_path) from None
                # link/replace 是可见性提交点。源临时文件已经关闭且发布保留同一 inode，
                # 因而 revision 可直接使用提交前 fstat；提交后的 stat/fsync/清理异常不能
                # 向客户端伪报“未发布”，否则 noclobber 重试会制造重复文件。
                committed = True
                published_stat = tmp_stat
                try:
                    os.fsync(parent_fd)
                except OSError:
                    # 目标已经原子可见；与目录复制相同，durability 告警不能改写 API 结果。
                    pass
                if existed and not exclusive:
                    # 文件发布与旧 hash 失效共用 storage→index 锁序，预检不会看见陈旧命中。
                    self._index_forget(rel_path)
            saved_path = target.relative_to(self.root).as_posix()
            self._notify_change("write", saved_path)
            return {
                "path": saved_path,
                "size": size,
                "_revision": f"{published_stat.st_dev}:{published_stat.st_ino}:{published_stat.st_mtime_ns}:{size}",
            }
        finally:
            active_error = sys.exc_info()[0] is not None
            cleanup_error: OSError | None = None
            if root_fd is not None:
                try:
                    os.close(root_fd)
                except OSError as exc:
                    cleanup_error = exc
            if parent_fd is not None:
                try:
                    os.close(parent_fd)
                except OSError as exc:
                    cleanup_error = cleanup_error or exc
            if not keep_for_retry:
                try:
                    original_tmp.unlink(missing_ok=True)
                except OSError as exc:
                    cleanup_error = cleanup_error or exc
            if cleanup_error is not None and not committed and not active_error:
                raise cleanup_error

    def save_bytes(
        self,
        rel_path: str,
        data: bytes,
        exclusive: bool = False,
        *,
        _allow_internal: bool = False,
    ) -> dict[str, Any]:
        """通过安全临时文件原子写入；exclusive=True 时不覆盖已有目标。"""
        tmp, stream = self.create_temp_file()
        try:
            with stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            info = self.publish_temp(
                rel_path,
                tmp,
                exclusive=exclusive,
                _allow_internal=_allow_internal,
            )
            info.pop("_revision", None)
            return info
        finally:
            tmp.unlink(missing_ok=True)

    def mkdir(self, rel_path: str) -> None:
        with self._locked_mutation():
            self.resolve(rel_path)
            parent_fd, leaf = self._open_parent_fd(rel_path, create=True)
            try:
                try:
                    value = self._lstat_at(parent_fd, leaf)
                    if not stat.S_ISDIR(value.st_mode):
                        raise FileExistsError(rel_path)
                except FileNotFoundError:
                    os.mkdir(leaf, mode=0o700, dir_fd=parent_fd)
                    os.fsync(parent_fd)
            finally:
                os.close(parent_fd)

    def rename(self, src: str, dst: str) -> None:
        # POSIX rename 会覆盖 dst：两侧索引都失效（src 移走，dst 旧内容被替换）
        with self._locked_mutation():
            source = self.resolve(src)
            target = self.resolve(dst)
            source_rel = source.relative_to(self.root).as_posix()
            target_rel = target.relative_to(self.root).as_posix()
            self._secure_rename(source_rel, target_rel, overwrite=True)
            self._index_forget(source_rel, recursive=True)
            self._index_forget(target_rel, recursive=True)
            self._notify_change("rename", source_rel, target_rel)

    def move(self, src: str, dst_dir: str, overwrite: bool = False) -> None:
        with self._locked_mutation():
            source = self.resolve(src)
            destination_dir = self.resolve(dst_dir)
            if not destination_dir.is_dir():
                raise NotADirectoryError(dst_dir)
            target_rel = (destination_dir / source.name).relative_to(self.root).as_posix()
            source_rel = source.relative_to(self.root).as_posix()
            if source_rel == target_rel:
                if not overwrite:
                    raise FileExistsError(f"目标已存在: {target_rel}")
                return
            self._secure_rename(source_rel, target_rel, overwrite=overwrite)
            self._index_forget(source_rel, recursive=True)
            self._index_forget(target_rel, recursive=True)
            self._notify_change("move", source_rel, target_rel)

    def delete(self, rel_path: str) -> None:
        with self._locked_mutation():
            p = self.resolve(rel_path)
            normalized = p.relative_to(self.root).as_posix()
            self._delete_rel_at(normalized)
            self._index_forget(normalized, recursive=True)
            self._notify_change("delete", normalized)

    def exists(self, rel_path: str) -> bool:
        return self.resolve(rel_path).exists()

    def current_revision(self, rel_path: str) -> str | None:
        """返回文件系统版本标识；不存在、目录或已被替换时用于判定索引失效。"""
        p = self.resolve(rel_path)
        try:
            value = p.stat()
        except FileNotFoundError:
            return None
        if not p.is_file():
            return None
        return f"{value.st_dev}:{value.st_ino}:{value.st_mtime_ns}:{value.st_size}"

    # ---------- 回收站（误删保护） ----------
    TRASH_DIR = ".trash"

    @property
    def trash_root(self) -> Path:
        return self.root / self.TRASH_DIR

    def _resolve_trash(self, rel_path: str) -> Path:
        """安全解析回收站相对路径：防穿越，并拒绝任一符号链接组件。"""
        relative = Path(rel_path)
        if relative.is_absolute() or ".." in relative.parts:
            raise PermissionError(f"回收站路径越界: {rel_path}")
        root = self.trash_root
        if root.is_symlink():
            raise PermissionError("回收站路径越界(符号链接)")
        node = root
        for part in relative.parts:
            if part in ("", "."):
                continue
            node = node / part
            if node.is_symlink():
                raise PermissionError(f"回收站路径越界(符号链接): {rel_path}")
        try:
            resolved = node.resolve()
            resolved.relative_to(root.resolve())
        except (OSError, RuntimeError, ValueError) as exc:
            raise PermissionError(f"回收站路径越界: {rel_path}") from exc
        return resolved

    def move_to_trash(self, rel_path: str) -> dict[str, Any]:
        """移到回收站；每次删除都有唯一 trash_id，同一路径可保留多个历史版本。"""
        with self._locked_mutation():
            p = self.resolve(rel_path)
            if not p.exists():
                raise FileNotFoundError(rel_path)
            normalized = p.relative_to(self.root).as_posix()
            trash_id = normalized
            while (
                self._resolve_trash(trash_id).exists()
                or self._resolve_trash(f"{trash_id}.meta.json").exists()
            ):
                trash_id = f"{normalized}.{time.time_ns()}.{secrets.token_hex(4)}"
            self._resolve_trash(trash_id)
            trash_rel = f"{self.TRASH_DIR}/{trash_id}"
            _target, _existed, moved_stat = self._secure_rename(
                normalized,
                trash_rel,
                overwrite=False,
                create_destination_parent=True,
            )
            meta = {
                "path": normalized,
                "trash_id": trash_id,
                "trash_path": trash_id,
                "deleted_at": time.time(),
                "size": moved_stat.st_size if stat.S_ISREG(moved_stat.st_mode) else 0,
                "is_dir": stat.S_ISDIR(moved_stat.st_mode),
            }
            try:
                self.save_bytes(
                    f"{self.TRASH_DIR}/{trash_id}.meta.json",
                    (json.dumps(meta, ensure_ascii=False) + "\n").encode("utf-8"),
                    exclusive=True,
                    _allow_internal=True,
                )
            except Exception:
                # 元数据未发布时回滚删除，避免产生不可恢复的孤儿条目。
                self._secure_rename(
                    trash_rel,
                    normalized,
                    overwrite=False,
                    create_destination_parent=True,
                )
                raise
            self._index_forget(normalized, recursive=True)  # 内容移入回收站：秒传条目失效
            self._notify_change("trash", normalized)
            return meta

    def _trash_entry(self, identifier: str) -> tuple[Path, Path, str]:
        """按 trash_id（兼容旧版原 path）解析内容、元数据与原始路径。"""
        meta_path = self._resolve_trash(f"{identifier}.meta.json")
        trash_rel = identifier
        original_rel = identifier
        if meta_path.is_file():
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
                if isinstance(meta, dict):
                    original_rel = str(meta.get("path") or identifier)
                    trash_rel = str(meta.get("trash_path") or identifier)
            except (OSError, ValueError, TypeError):
                pass
        self.resolve(original_rel)  # 同时校验恢复目标路径
        return self._resolve_trash(trash_rel), meta_path, original_rel

    def list_trash(self) -> list[dict[str, Any]]:
        """回收站列表（按删除时间倒序）"""
        if self.trash_root.is_symlink():
            raise PermissionError("回收站路径越界(符号链接)")
        if not self.trash_root.exists():
            return []
        items = []
        for meta_path in self.trash_root.rglob("*.meta.json"):
            try:
                if meta_path.is_symlink():
                    continue
                item = json.loads(meta_path.read_text(encoding="utf-8"))
                if not isinstance(item, dict):
                    continue
                trash_id = str(item.get("trash_id") or item.get("trash_path") or "")
                trash_path = self._resolve_trash(trash_id)
                if not trash_path.exists():
                    continue  # 恢复/清理已完成但 metadata 残留时不展示幽灵条目。
                self.resolve(str(item.get("path", "")))
                item["trash_id"] = trash_id
                items.append(item)
            except Exception:
                continue
        items.sort(key=lambda m: m.get("deleted_at", 0), reverse=True)
        return items

    def restore_from_trash(self, identifier: str) -> dict[str, Any]:
        """按 trash_id（兼容旧版原 path）恢复；原位置已有内容时报错。"""
        with self._locked_mutation():
            trash_path, meta_path, original_rel = self._trash_entry(identifier)
            if not trash_path.exists():
                raise FileNotFoundError(f"回收站中不存在: {identifier}")
            original = self.resolve(original_rel)
            trash_rel = f"{self.TRASH_DIR}/{trash_path.relative_to(self.trash_root).as_posix()}"
            meta_rel = f"{self.TRASH_DIR}/{meta_path.relative_to(self.trash_root).as_posix()}"
            self._secure_rename(
                trash_rel,
                original_rel,
                overwrite=False,
                create_destination_parent=True,
            )
            try:
                self._delete_rel_at(meta_rel, missing_ok=True)
            except OSError:
                # 内容已原子恢复；残留 metadata 会被 list_trash 隐藏并可由后续清理移除。
                pass
            restored = original.relative_to(self.root).as_posix()
            self._notify_change("restore", restored)
            return {"restored": original_rel}

    def _cleanup_orphan_trash_metadata(self) -> None:
        if not self.trash_root.exists() or self.trash_root.is_symlink():
            return
        with self._locked_mutation():
            for meta_path in self.trash_root.rglob("*.meta.json"):
                try:
                    if meta_path.is_symlink():
                        continue
                    item = json.loads(meta_path.read_text(encoding="utf-8"))
                    if not isinstance(item, dict):
                        continue
                    trash_id = str(item.get("trash_id") or item.get("trash_path") or "")
                    if self._resolve_trash(trash_id).exists():
                        continue
                    meta_rel = f"{self.TRASH_DIR}/{meta_path.relative_to(self.trash_root).as_posix()}"
                    self._delete_rel_at(meta_rel, missing_ok=True)
                except (OSError, PermissionError, ValueError, TypeError):
                    continue

    def purge_trash(self, identifier: str | None = None) -> dict[str, Any]:
        """彻底删除。identifier=None 清空整个回收站。"""
        removed = 0
        if identifier is not None:
            with self._locked_mutation():
                trash_path, meta_path, _original = self._trash_entry(identifier)
                trash_rel = f"{self.TRASH_DIR}/{trash_path.relative_to(self.trash_root).as_posix()}"
                meta_rel = f"{self.TRASH_DIR}/{meta_path.relative_to(self.trash_root).as_posix()}"
                removed = int(self._delete_rel_at(trash_rel, missing_ok=True))
                try:
                    self._delete_rel_at(meta_rel, missing_ok=True)
                except OSError:
                    # 内容已删除；残留 metadata 不再展示，后续清理可重试。
                    pass
        else:
            failures: list[str] = []
            for item in self.list_trash():
                trash_id = str(item.get("trash_id") or item["path"])
                try:
                    result = self.purge_trash(trash_id)
                    removed += int(result["removed"])
                except (OSError, PermissionError, ValueError, KeyError, TypeError) as exc:
                    failures.append(f"{trash_id}: {exc}")
            self._cleanup_orphan_trash_metadata()
            if failures:
                raise OSError("回收站仅部分清空: " + "; ".join(failures[:3]))
        return {"removed": removed}

    def cleanup_trash(self, days: int = 30) -> int:
        """清理超过 N 天的回收站条目（scheduler 每日调用）。"""
        cutoff = time.time() - days * 86400
        removed = 0
        for m in self.list_trash():
            if m.get("deleted_at", 0) < cutoff:
                try:
                    self.purge_trash(str(m.get("trash_id") or m["path"]))
                    removed += 1
                except Exception:
                    pass
        return removed

    # ---------- 写操作（AI 中心：Agent 能创建内容） ----------
    def write_text(self, rel_path: str, content: str) -> dict[str, Any]:
        """以 UTF-8/LF 原子创建或覆盖文本文件。"""
        existed = self.resolve(rel_path).exists()
        info = self.save_bytes(rel_path, content.encode("utf-8"))
        return {
            **info,
            "existed": existed,
            "action": "覆盖" if existed else "新建",
        }

    def append_text(self, rel_path: str, content: str) -> dict[str, Any]:
        """并发安全的原子追加：加锁后复制旧内容，追加 UTF-8，再一次性发布。"""
        with self._locked_mutation():
            p = self.resolve(rel_path)
            existed = p.exists()
            if existed and not p.is_file():
                raise IsADirectoryError(rel_path)
            tmp, stream = self.create_temp_file()
            try:
                with stream:
                    if existed:
                        with open(p, "rb") as source:
                            shutil.copyfileobj(source, stream, length=1024 * 1024)
                    stream.write(content.encode("utf-8"))
                    stream.flush()
                    os.fsync(stream.fileno())
                info = self.publish_temp(rel_path, tmp)
                info.pop("_revision", None)
            finally:
                tmp.unlink(missing_ok=True)
        return {
            **info,
            "existed": existed,
            "action": "追加" if existed else "新建",
        }

    def copy(self, src: str, dst: str, overwrite: bool = False) -> dict[str, Any]:
        """以 nofollow 源 FD 复制文件或目录；文件通过临时文件原子发布。"""
        with self._locked_mutation():
            source = self.resolve(src)
            destination_path = self.resolve(dst)
            if source == destination_path:
                raise ValueError(f"源与目标相同: {src}")
            try:
                destination_path.relative_to(source)
            except ValueError:
                pass
            else:
                raise ValueError("目标不能位于源目录内部")
            source_rel = source.relative_to(self.root).as_posix()
            destination = destination_path.relative_to(self.root).as_posix()
            source_fd, leaf = self._open_parent_fd(source_rel, create=False)
            try:
                value = self._lstat_at(source_fd, leaf)
                if stat.S_ISDIR(value.st_mode):
                    self._copy_directory_at(source_fd, leaf, destination, overwrite=overwrite)
                    if overwrite:
                        self._index_forget(destination, recursive=True)
                    is_dir = True
                elif stat.S_ISREG(value.st_mode):
                    self._copy_file_at(source_fd, leaf, destination, overwrite=overwrite)
                    is_dir = False
                else:
                    raise ValueError(f"不支持复制特殊文件: {src}")
            finally:
                os.close(source_fd)
            self._notify_change("copy", destination)
            return {
                "src": src,
                "dst": destination,
                "is_dir": is_dir,
            }

    def stat(self, rel_path: str) -> dict[str, Any]:
        p = self.resolve(rel_path)
        st = p.stat()
        mime, _ = mimetypes.guess_type(p.name)
        return {
            "name": p.name,
            "path": rel_path,
            "is_dir": p.is_dir(),
            "size": st.st_size,
            "mtime": st.st_mtime,
            "mime": mime or "application/octet-stream",
        }

    def disk_usage(self) -> dict[str, int]:
        st = shutil.disk_usage(self.root)
        return {"total": st.total, "used": st.used, "free": st.free}
