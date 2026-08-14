"""上传去重索引（秒传机制）。

存储：system/upload-index.json，两个映射：
  by_md5: {md5: {path, size, at}}   —— 内容去重查找
  by_path: {path: md5}             —— 同名覆盖/删除时反向清理

用途：相册同步上传带 md5 → 服务端命中且文件仍在 → 秒传（跳过传输与索引）。
"""
from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any


class UploadIndex:
    """Container 持有单实例；写操作加锁。"""

    def __init__(self, path: Path, storage=None):
        self._path = Path(path)
        self._storage = storage  # 用于校验索引是否过期（文件还在不在）
        self._lock = threading.RLock()
        self._by_md5: dict[str, dict[str, Any]] = {}
        self._by_path: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                self._by_md5 = data.get("by_md5", {})
                self._by_path = data.get("by_path", {})
        except (json.JSONDecodeError, OSError):
            pass  # 坏文件：从空索引开始，不阻塞

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(".tmp")
        tmp.write_text(json.dumps(
            {"by_md5": self._by_md5, "by_path": self._by_path},
            ensure_ascii=False, indent=2,
        ), encoding="utf-8")
        tmp.replace(self._path)

    # ---- 查询 ----
    def lookup(self, md5: str) -> dict[str, Any] | None:
        """内容去重命中（且文件仍存在）→ 返回 {path, size, at}；否则 None 并清理陈旧条目。"""
        with self._lock:
            entry = self._by_md5.get(md5)
            if entry is None:
                return None
            path = entry.get("path", "")
            if self._storage is not None and not self._storage.exists(path):
                # 文件已被删除/移动：索引过期，清理
                self._by_md5.pop(md5, None)
                self._by_path.pop(path, None)
                self._save()
                return None
            return dict(entry)

    # ---- 写入 ----
    def record(self, md5: str, path: str, size: int) -> None:
        """登记新上传；同路径的旧条目先清掉（覆盖场景）。"""
        with self._lock:
            old_md5 = self._by_path.get(path)
            if old_md5 and old_md5 != md5:
                self._by_md5.pop(old_md5, None)
            self._by_md5[md5] = {"path": path, "size": size, "at": time.time()}
            self._by_path[path] = md5
            self._save()

    def forget_path(self, path: str) -> None:
        """文件被删除/移动时清理索引。"""
        with self._lock:
            md5 = self._by_path.pop(path, None)
            if md5:
                self._by_md5.pop(md5, None)
                self._save()