"""设备注册表（domain）：App 连接登记 / 心跳 / 列表 / 移除。

存储：system/devices.json（运行时数据，与 sessions 同级，不入 git）。
线程安全：读多写少，写操作加锁。
"""
from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Any


class DeviceRegistry:
    """设备登记表（Container 持有单实例）。"""

    def __init__(self, path: Path):
        self._path = Path(path)
        self._lock = threading.Lock()
        self._devices: dict[str, dict[str, Any]] = {}
        self._load()

    # ---- 持久化 ----
    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
            if isinstance(data, list):
                self._devices = {d.get("device_id", ""): d for d in data if d.get("device_id")}
        except (json.JSONDecodeError, OSError):
            # 坏文件不影响启动：从空表开始
            self._devices = {}

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(
            prefix=f".{self._path.name}.", suffix=".tmp", dir=self._path.parent,
        )
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                fd = -1
                json.dump(
                    list(self._devices.values()),
                    stream,
                    ensure_ascii=False,
                    indent=2,
                )
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            tmp.chmod(0o600)
            tmp.replace(self._path)  # 原子替换
        except Exception:
            if fd >= 0:
                os.close(fd)
            tmp.unlink(missing_ok=True)
            raise

    # ---- 操作 ----
    def register(self, device_id: str, name: str = "", model: str = "",
                 platform: str = "android", app_version: str = "",
                 sync: dict[str, Any] | None = None) -> dict[str, Any]:
        """登记/心跳：按 device_id upsert，刷新活跃时间，可选更新同步状态。"""
        now = time.time()
        with self._lock:
            dev = self._devices.get(device_id)
            if dev is None:
                dev = {"device_id": device_id, "first_seen": now}
                self._devices[device_id] = dev
            dev["name"] = name or dev.get("name") or "安卓设备"
            dev["model"] = model or dev.get("model") or ""
            dev["platform"] = platform or dev.get("platform") or "android"
            dev["app_version"] = app_version or dev.get("app_version") or ""
            dev["last_seen"] = now
            if sync is not None:
                dev["sync"] = {**dev.get("sync", {}), **sync}
            self._save()
            return dict(dev)

    def list(self) -> list[dict[str, Any]]:
        """全部设备，按最近活跃排序。"""
        with self._lock:
            return sorted(
                self._devices.values(),
                key=lambda d: d.get("last_seen", 0),
                reverse=True,
            )

    def remove(self, device_id: str) -> bool:
        with self._lock:
            if device_id in self._devices:
                del self._devices[device_id]
                self._save()
                return True
            return False
