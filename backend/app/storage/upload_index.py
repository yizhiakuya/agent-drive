"""上传去重索引（秒传机制）。

存储：system/upload-index.json，两个映射：
  by_md5: {md5: {path, size, at}}   —— 内容去重查找
  by_path: {path: md5}              —— 同名覆盖/删除时反向清理

上传完成后登记服务端实算 MD5；相册同步先调用 verified-only 预检，命中则免传。
索引更新使用 sidecar 文件锁和唯一临时文件，支持多个进程安全地 reload/modify/save。
"""
from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

try:
    import fcntl
except ImportError:  # pragma: no cover - Windows compatibility
    fcntl = None  # type: ignore[assignment]


class UploadIndex:
    """Container 持有单实例；磁盘事务同时保护多线程和 POSIX 多进程。"""

    def __init__(self, path: Path, storage=None):
        self._path = Path(path)
        self._lock_path = self._path.with_name(self._path.name + ".lock")
        self._storage = storage
        self._lock = threading.RLock()
        self._by_md5: dict[str, dict[str, Any]] = {}
        self._by_path: dict[str, str] = {}
        self._load()

    def _reload_locked(self) -> None:
        by_md5: dict[str, dict[str, Any]] = {}
        by_path: dict[str, str] = {}
        if self._path.exists():
            try:
                data = json.loads(self._path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError, UnicodeDecodeError):
                data = {}
            if isinstance(data, dict):
                raw_md5 = data.get("by_md5", {})
                raw_path = data.get("by_path", {})
                if isinstance(raw_md5, dict):
                    by_md5 = {
                        str(key): value
                        for key, value in raw_md5.items()
                        if isinstance(value, dict)
                    }
                if isinstance(raw_path, dict):
                    by_path = {
                        str(key): str(value)
                        for key, value in raw_path.items()
                        if isinstance(value, str)
                    }
        self._by_md5 = by_md5
        self._by_path = by_path

    def _load(self) -> None:
        # _disk_transaction() 进入时已 reload；这里只需走一次完整锁事务。
        with self._disk_transaction():
            pass

    @contextmanager
    def _storage_transaction(self) -> Iterator[None]:
        """统一锁顺序：LocalStorage mutation lock → UploadIndex disk lock。"""
        guard = getattr(self._storage, "_locked_mutation", None)
        if callable(guard):
            with guard():
                yield
        else:
            yield

    @contextmanager
    def _disk_transaction(self) -> Iterator[None]:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock:
            fd = os.open(
                self._lock_path,
                os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0),
                0o600,
            )
            try:
                os.fchmod(fd, 0o600)
                if fcntl is not None:
                    fcntl.flock(fd, fcntl.LOCK_EX)
                self._reload_locked()
                yield
            finally:
                if fcntl is not None:
                    fcntl.flock(fd, fcntl.LOCK_UN)
                os.close(fd)

    def _save_locked(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(
            prefix=f".{self._path.name}.", suffix=".tmp", dir=self._path.parent,
        )
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                json.dump(
                    {"by_md5": self._by_md5, "by_path": self._by_path},
                    stream,
                    ensure_ascii=False,
                    indent=2,
                )
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(tmp, 0o600)
            os.replace(tmp, self._path)
            if fcntl is not None:
                parent_fd = os.open(
                    self._path.parent,
                    os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
                )
                try:
                    os.fsync(parent_fd)
                finally:
                    os.close(parent_fd)
        except Exception:
            tmp.unlink(missing_ok=True)
            raise

    # ---- 查询 ----
    def lookup(self, md5: str, *, verified_only: bool = False) -> dict[str, Any] | None:
        """内容去重命中且文件仍是登记版本时返回条目。

        ``verified_only`` 只接受服务端实算且绑定发布 revision 的条目；升级前
        没有这两个字段的客户端声明条目不可用于免传预检。
        """
        with self._storage_transaction(), self._disk_transaction():
            entry = self._by_md5.get(md5)
            if entry is None or (
                verified_only
                and (entry.get("verified") is not True or not entry.get("revision"))
            ):
                return None
            path = str(entry.get("path", ""))
            revision = entry.get("revision")
            stale = False
            if self._storage is not None:
                stale = not self._storage.exists(path)
                if not stale and revision and hasattr(self._storage, "current_revision"):
                    stale = self._storage.current_revision(path) != revision
            if stale:
                self._by_md5.pop(md5, None)
                if self._by_path.get(path) == md5:
                    self._by_path.pop(path, None)
                self._save_locked()
                return None
            return dict(entry)

    # ---- 写入 ----
    def record(
        self,
        md5: str,
        path: str,
        size: int,
        *,
        verified: bool = True,
        revision: str | None = None,
    ) -> bool:
        """条件登记新上传；发布版本已被覆盖时拒绝写入陈旧 MD5。"""
        with self._storage_transaction(), self._disk_transaction():
            if (
                revision
                and self._storage is not None
                and hasattr(self._storage, "current_revision")
                and self._storage.current_revision(path) != revision
            ):
                return False
            old_md5 = self._by_path.get(path)
            if old_md5 and old_md5 != md5:
                self._by_md5.pop(old_md5, None)
            for other, other_md5 in list(self._by_path.items()):
                if other_md5 == md5 and other != path:
                    self._by_md5.pop(md5, None)
                    del self._by_path[other]
            self._by_md5[md5] = {
                "path": path,
                "size": size,
                "at": time.time(),
                "verified": verified,
                **({"revision": revision} if revision else {}),
            }
            self._by_path[path] = md5
            self._save_locked()
            return True

    def forget_path(self, path: str, *, recursive: bool = False) -> None:
        """文件或目录树被删除/移动时清理索引。"""
        with self._storage_transaction(), self._disk_transaction():
            targets = [path]
            if recursive:
                prefix = path.rstrip("/") + "/"
                targets.extend(other for other in self._by_path if other.startswith(prefix))
            changed = False
            for target in targets:
                md5 = self._by_path.pop(target, None)
                if md5:
                    self._by_md5.pop(md5, None)
                    changed = True
            if changed:
                self._save_locked()
