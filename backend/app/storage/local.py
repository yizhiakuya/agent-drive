"""本地文件系统存储实现（Storage 协议）。

路径安全：resolve() 防穿越 + 组件级拒绝符号链接；写操作原子（tmp + replace）。
可选挂载 UploadIndex：所有内容变更自动失效秒传索引条目。"""
from __future__ import annotations

import json
import mimetypes
import os
import shutil
import time
import uuid
from collections.abc import Callable
from pathlib import Path
from typing import Any


class LocalStorage:
    def __init__(self, root: Path | str):
        self.root = Path(root).resolve()  # 规范化根路径（防根路径含符号链接绕过）
        self.root.mkdir(parents=True, exist_ok=True)
        self.index: Any | None = None  # UploadIndex 可选挂载（container 组装后注入）
        self._change_listeners: list[Callable[[str, list[str]], None]] = []

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

    def _index_forget(self, rel_path: str) -> None:
        """内容将被改变/移走：失效其秒传条目（索引失败不阻塞主流程）。"""
        if self.index is not None:
            try:
                self.index.forget_path(rel_path)
            except Exception:
                pass

    # ---------- 路径安全 ----------
    def resolve(self, rel_path: str) -> Path:
        """把相对路径安全解析为绝对路径，防穿越 + 拒绝符号链接。

        业务从不产生符号链接：任何出现在数据树内的链接（指向 system/、
        网盘外等）一律拒绝，堵死 symlink 逃逸读取敏感文件。
        """
        raw = self.root / rel_path
        # 组件级检查（在 resolve 之前：resolve 会跟随链接抹掉证据）
        node = raw
        while node != self.root:
            if node.is_symlink():
                raise PermissionError(f"路径越界(符号链接): {rel_path}")
            node = node.parent
        p = raw.resolve()
        if not p.is_relative_to(self.root):
            raise PermissionError(f"路径越界: {rel_path}")
        return p

    # ---------- 文件操作 ----------
    def list_dir(self, rel_path: str = "") -> list[dict[str, Any]]:
        d = self.resolve(rel_path)
        if not d.is_dir():
            raise NotADirectoryError(rel_path)
        items = []
        with os.scandir(d) as it:
            for e in sorted(it, key=lambda x: (x.is_file(), x.name.lower())):
                if e.name in (".index", ".trash"):
                    continue  # 隐藏索引/回收站目录
                is_file = e.is_file()
                st = e.stat()  # DirEntry 已缓存 stat，无需二次系统调用
                rel = Path(e.path).relative_to(self.root).as_posix()
                items.append({
                    "name": e.name,
                    "path": rel,
                    "is_dir": not is_file,
                    "size": st.st_size if is_file else 0,
                    "mtime": st.st_mtime,
                })
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

    def save_bytes(self, rel_path: str, data: bytes, exclusive: bool = False) -> dict[str, Any]:
        """原子写入。exclusive=True 时目标已存在则抛 FileExistsError（防同名竞态）。

        - 覆盖写：先失效旧内容的秒传索引（否则旧 md5 会命中新文件，内容错位）
        - tmp + os.replace 原子替换；exclusive 用 os.link 原子不覆盖
        """
        p = self.resolve(rel_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        if exclusive and p.exists():
            raise FileExistsError(rel_path)
        existed = p.exists()
        tmp = p.with_name(f".{p.name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp")
        tmp.write_bytes(data)
        try:
            if exclusive:
                try:
                    os.link(tmp, p)  # 原子创建，目标存在则 EEXIST（不覆盖）
                except FileExistsError:
                    raise FileExistsError(rel_path) from None
                finally:
                    tmp.unlink(missing_ok=True)
            else:
                os.replace(tmp, p)  # 原子替换
        finally:
            tmp.unlink(missing_ok=True)
        if existed and not exclusive:
            self._index_forget(rel_path)
        saved_path = p.relative_to(self.root).as_posix()
        self._notify_change("write", saved_path)
        return {"path": saved_path, "size": len(data)}

    def mkdir(self, rel_path: str) -> None:
        self.resolve(rel_path).mkdir(parents=True, exist_ok=True)

    def rename(self, src: str, dst: str) -> None:
        # POSIX rename 会覆盖 dst：两侧索引都失效（src 移走，dst 旧内容被替换）
        source = self.resolve(src)
        target = self.resolve(dst)
        source_rel = source.relative_to(self.root).as_posix()
        target_rel = target.relative_to(self.root).as_posix()
        source.rename(target)
        self._index_forget(source_rel)
        self._index_forget(target_rel)
        self._notify_change("rename", source_rel, target_rel)

    def move(self, src: str, dst_dir: str, overwrite: bool = False) -> None:
        p = self.resolve(src)
        target = self.resolve(dst_dir) / p.name
        if target.exists() and not overwrite:
            raise FileExistsError(f"目标已存在: {target.relative_to(self.root).as_posix()}（需 overwrite=true）")
        source_rel = p.relative_to(self.root).as_posix()
        target_rel = target.relative_to(self.root).as_posix()
        shutil.move(str(p), str(target))
        self._index_forget(source_rel)
        self._index_forget(target_rel)
        self._notify_change("move", source_rel, target_rel)

    def delete(self, rel_path: str) -> None:
        p = self.resolve(rel_path)
        normalized = p.relative_to(self.root).as_posix()
        if p.is_dir():
            shutil.rmtree(p)
        else:
            p.unlink()
        self._index_forget(normalized)
        self._notify_change("delete", normalized)

    def exists(self, rel_path: str) -> bool:
        return self.resolve(rel_path).exists()

    # ---------- 回收站（误删保护） ----------
    TRASH_DIR = ".trash"

    @property
    def trash_root(self) -> Path:
        return self.root / self.TRASH_DIR

    def move_to_trash(self, rel_path: str) -> dict[str, Any]:
        """移到回收站（保留原路径结构，记录删除时间）"""
        p = self.resolve(rel_path)
        if not p.exists():
            raise FileNotFoundError(rel_path)
        normalized = p.relative_to(self.root).as_posix()
        dest = self.trash_root / normalized
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            # 同名已存在：加时间戳后缀
            dest = self.trash_root / f"{normalized}.{int(time.time())}"
        shutil.move(str(p), str(dest))
        self._index_forget(normalized)  # 内容移入回收站：秒传条目失效
        # 记录元信息
        meta_path = self.trash_root / f"{normalized}.meta.json"
        meta = {
            "path": normalized,
            "trash_path": dest.relative_to(self.trash_root).as_posix(),
            "deleted_at": time.time(),
            "size": dest.stat().st_size if dest.is_file() else 0,
            "is_dir": dest.is_dir(),
        }
        meta_path.parent.mkdir(parents=True, exist_ok=True)
        meta_path.write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        self._notify_change("trash", normalized)
        return meta

    def list_trash(self) -> list[dict[str, Any]]:
        """回收站列表（按删除时间倒序）"""
        if not self.trash_root.exists():
            return []
        items = []
        for meta_path in self.trash_root.rglob("*.meta.json"):
            try:
                items.append(json.loads(meta_path.read_text(encoding="utf-8")))
            except Exception:
                continue
        items.sort(key=lambda m: m.get("deleted_at", 0), reverse=True)
        return items

    def restore_from_trash(self, rel_path: str) -> dict[str, Any]:
        """从回收站恢复（目标已存在时报错）"""
        trash_path = self.trash_root / rel_path
        if not trash_path.exists():
            raise FileNotFoundError(f"回收站中不存在: {rel_path}")
        original = self.root / rel_path
        if original.exists():
            raise FileExistsError(f"原位置已有文件: {rel_path}")
        original.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(trash_path), str(original))
        (self.trash_root / f"{rel_path}.meta.json").unlink(missing_ok=True)
        self._notify_change("restore", original.relative_to(self.root).as_posix())
        return {"restored": rel_path}

    def purge_trash(self, rel_path: str | None = None) -> dict[str, Any]:
        """彻底删除。rel_path=None 清空整个回收站。"""
        removed = 0
        if rel_path is not None:
            trash_path = self.trash_root / rel_path
            if trash_path.exists():
                if trash_path.is_dir():
                    shutil.rmtree(trash_path)
                else:
                    trash_path.unlink()
                removed = 1
            (self.trash_root / f"{rel_path}.meta.json").unlink(missing_ok=True)
        else:
            metas = list(self.trash_root.rglob("*.meta.json"))
            for meta_path in metas:
                try:
                    m = json.loads(meta_path.read_text(encoding="utf-8"))
                    tp = self.trash_root / m["trash_path"]
                    if tp.exists():
                        if tp.is_dir():
                            shutil.rmtree(tp)
                        else:
                            tp.unlink()
                        removed += 1
                except Exception:
                    pass
                meta_path.unlink(missing_ok=True)
        return {"removed": removed}

    def cleanup_trash(self, days: int = 30) -> int:
        """清理超过 N 天的回收站条目（scheduler 每日调用）。"""
        cutoff = time.time() - days * 86400
        removed = 0
        for m in self.list_trash():
            if m.get("deleted_at", 0) < cutoff:
                try:
                    self.purge_trash(m["path"])
                    removed += 1
                except Exception:
                    pass
        return removed

    # ---------- 写操作（AI 中心：Agent 能创建内容） ----------
    def write_text(self, rel_path: str, content: str) -> dict[str, Any]:
        """创建或覆盖文本文件。newline="\n"：跨平台一致（Windows 不转 CRLF）。"""
        p = self.resolve(rel_path)
        existed = p.exists()
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        if existed:
            self._index_forget(rel_path)  # 内容已变：旧 md5 失效
        normalized = p.relative_to(self.root).as_posix()
        self._notify_change("write", normalized)
        return {
            "path": normalized,
            "size": len(content.encode("utf-8")),
            "existed": existed,
            "action": "覆盖" if existed else "新建",
        }

    def append_text(self, rel_path: str, content: str) -> dict[str, Any]:
        """追加内容到文本文件（不存在则创建）。"""
        p = self.resolve(rel_path)
        existed = p.exists()
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, "a", encoding="utf-8", newline="\n") as f:
            f.write(content)
        self._index_forget(rel_path)  # 内容已变：旧 md5 失效
        normalized = p.relative_to(self.root).as_posix()
        self._notify_change("write", normalized)
        return {
            "path": normalized,
            "size": p.stat().st_size,
            "existed": existed,
            "action": "追加" if existed else "新建",
        }

    def copy(self, src: str, dst: str, overwrite: bool = False) -> dict[str, Any]:
        """复制文件或目录到新位置。"""
        s_p = self.resolve(src)
        d_p = self.resolve(dst)
        if s_p == d_p:
            raise ValueError(f"源与目标相同: {src}")
        if d_p.exists() and not overwrite:
            raise FileExistsError(f"目标已存在: {dst}（需 overwrite=true）")
        if not s_p.exists():
            raise FileNotFoundError(src)
        if s_p.is_dir():
            shutil.copytree(s_p, d_p)
        else:
            d_p.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(s_p, d_p)
        if d_p.exists() and overwrite:
            self._index_forget(dst)  # 目标旧内容被覆盖：失效其秒传条目
        destination = d_p.relative_to(self.root).as_posix()
        self._notify_change("copy", destination)
        return {
            "src": src,
            "dst": destination,
            "is_dir": s_p.is_dir(),
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
