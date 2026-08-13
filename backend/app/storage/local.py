"""本地文件系统存储实现（Storage 协议）。

路径安全：resolve() 防穿越；写操作幂等。M2 增加 s3.py 实现同协议。"""
from __future__ import annotations

import mimetypes
import shutil
from pathlib import Path
from typing import Any


class LocalStorage:
    def __init__(self, root: Path | str):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    # ---------- 路径安全 ----------
    def resolve(self, rel_path: str) -> Path:
        """把相对路径安全解析为绝对路径，防止路径穿越。"""
        p = (self.root / rel_path).resolve()
        if not p.is_relative_to(self.root):
            raise PermissionError(f"路径越界: {rel_path}")
        return p

    # ---------- 文件操作 ----------
    def list_dir(self, rel_path: str = "") -> list[dict[str, Any]]:
        d = self.resolve(rel_path)
        if not d.is_dir():
            raise NotADirectoryError(rel_path)
        items = []
        for p in sorted(d.iterdir(), key=lambda x: (x.is_file(), x.name.lower())):
            rel = p.relative_to(self.root).as_posix()
            items.append({
                "name": p.name,
                "path": rel,
                "is_dir": p.is_dir(),
                "size": p.stat().st_size if p.is_file() else 0,
                "mtime": p.stat().st_mtime,
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

    def save_bytes(self, rel_path: str, data: bytes) -> dict[str, Any]:
        p = self.resolve(rel_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        return {"path": p.relative_to(self.root).as_posix(), "size": len(data)}

    def mkdir(self, rel_path: str) -> None:
        self.resolve(rel_path).mkdir(parents=True, exist_ok=True)

    def rename(self, src: str, dst: str) -> None:
        self.resolve(src).rename(self.resolve(dst))

    def move(self, src: str, dst_dir: str, overwrite: bool = False) -> None:
        p = self.resolve(src)
        target = self.resolve(dst_dir) / p.name
        if target.exists() and not overwrite:
            raise FileExistsError(f"目标已存在: {target.relative_to(self.root).as_posix()}（需 overwrite=true）")
        shutil.move(str(p), str(target))

    def delete(self, rel_path: str) -> None:
        p = self.resolve(rel_path)
        if p.is_dir():
            shutil.rmtree(p)
        else:
            p.unlink()

    def exists(self, rel_path: str) -> bool:
        return self.resolve(rel_path).exists()

    # ---------- 写操作（AI 中心：Agent 能创建内容） ----------
    def write_text(self, rel_path: str, content: str) -> dict[str, Any]:
        """创建或覆盖文本文件。"""
        p = self.resolve(rel_path)
        existed = p.exists()
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
        return {
            "path": p.relative_to(self.root).as_posix(),
            "size": len(content.encode("utf-8")),
            "existed": existed,
            "action": "覆盖" if existed else "新建",
        }

    def append_text(self, rel_path: str, content: str) -> dict[str, Any]:
        """追加内容到文本文件（不存在则创建）。"""
        p = self.resolve(rel_path)
        existed = p.exists()
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, "a", encoding="utf-8") as f:
            f.write(content)
        return {
            "path": p.relative_to(self.root).as_posix(),
            "size": p.stat().st_size,
            "existed": existed,
            "action": "追加" if existed else "新建",
        }

    def copy(self, src: str, dst: str, overwrite: bool = False) -> dict[str, Any]:
        """复制文件或目录到新位置。"""
        s_p = self.resolve(src)
        d_p = self.resolve(dst)
        if d_p.exists() and not overwrite:
            raise FileExistsError(f"目标已存在: {dst}（需 overwrite=true）")
        if not s_p.exists():
            raise FileNotFoundError(src)
        if s_p.is_dir():
            shutil.copytree(s_p, d_p)
        else:
            d_p.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(s_p, d_p)
        return {
            "src": src,
            "dst": d_p.relative_to(self.root).as_posix(),
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
