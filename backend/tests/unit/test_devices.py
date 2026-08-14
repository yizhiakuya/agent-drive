"""设备注册表单测：upsert / 列表排序 / 移除 / 持久化 / 坏文件容错。"""
from __future__ import annotations

import time

from app.devices.registry import DeviceRegistry


def test_register_upsert_and_list(tmp_path):
    reg = DeviceRegistry(tmp_path / "devices.json")
    reg.register("dev-1", name="Xiaomi 14", app_version="1.0.12")
    time.sleep(0.01)  # 确保 last_seen 有序
    reg.register("dev-2", name="Pixel 8")
    reg.register("dev-1", name="Xiaomi 14 新名字", sync={"enabled": True, "last_synced_count": 3})

    devices = reg.list()
    assert len(devices) == 2
    assert devices[0]["device_id"] == "dev-1"  # 最近活跃在前
    assert devices[0]["name"] == "Xiaomi 14 新名字"
    assert devices[0]["sync"]["enabled"] is True
    assert devices[0]["sync"]["last_synced_count"] == 3


def test_remove_and_persistence(tmp_path):
    path = tmp_path / "devices.json"
    reg = DeviceRegistry(path)
    reg.register("dev-1")
    assert reg.remove("dev-1") is True
    assert reg.remove("dev-1") is False

    reg.register("dev-2", name="A")
    reg2 = DeviceRegistry(path)  # 重新加载验证持久化
    assert [d["device_id"] for d in reg2.list()] == ["dev-2"]


def test_bad_file_tolerated(tmp_path):
    path = tmp_path / "devices.json"
    path.write_text("{broken", encoding="utf-8")
    reg = DeviceRegistry(path)
    assert reg.list() == []
    reg.register("dev-3")  # 坏文件后仍可正常写入
    assert len(reg.list()) == 1