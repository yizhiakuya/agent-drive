"""v1 路由聚合"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from . import chat, config, files, sessions
from ..deps import get_container

api_v1 = APIRouter(prefix="/api/v1")
api_v1.include_router(chat.router)
api_v1.include_router(config.router)
api_v1.include_router(files.router)
api_v1.include_router(sessions.router)


@api_v1.get("/status")
async def system_status(container=Depends(get_container)):
    """系统状态（Onboarding 判断是否已配置）"""
    return container.onboarding.status()
