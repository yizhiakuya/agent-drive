"""v1 设备路由：App 连接登记 / 心跳 / 设备列表 / 移除。"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..deps import get_container

router = APIRouter(prefix="/devices", tags=["devices"])


class SyncState(BaseModel):
    enabled: bool = False
    wifi_only: bool = True
    interval_hours: float = 6.0
    last_sync_at: float | None = None
    last_synced_count: int = 0
    last_error: str | None = None


class DeviceRegister(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    name: str = ""
    model: str = ""
    platform: str = "android"
    app_version: str = ""
    sync: SyncState | None = None


@router.get("")
async def list_devices(container=Depends(get_container)):
    return {"devices": container.devices.list()}


@router.post("/register")
async def register_device(payload: DeviceRegister, container=Depends(get_container)):
    """App 登记/心跳：按 device_id upsert，刷新活跃时间。"""
    dev = container.devices.register(
        device_id=payload.device_id,
        name=payload.name,
        model=payload.model,
        platform=payload.platform,
        app_version=payload.app_version,
        sync=payload.sync.model_dump() if payload.sync else None,
    )
    return dev


@router.delete("/{device_id}")
async def remove_device(device_id: str, container=Depends(get_container)):
    if not container.devices.remove(device_id):
        raise HTTPException(404, "设备不存在")
    revoked = container.auth.revoke_device(device_id)  # 吊销令牌：App 立即失联
    return {"removed": device_id, "tokens_revoked": revoked}
