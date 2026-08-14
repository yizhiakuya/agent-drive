"""v1 路由聚合：auth 公开，其余全部走统一鉴权（get_owner）。"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from ..deps import get_container, get_owner
from . import auth, automation, chat, config, devices, files, sessions

api_v1 = APIRouter(prefix="/api/v1")
api_v1.include_router(auth.router)  # 登录/设密公开
api_v1.include_router(automation.router, dependencies=[Depends(get_owner)])
api_v1.include_router(chat.router, dependencies=[Depends(get_owner)])
api_v1.include_router(config.router, dependencies=[Depends(get_owner)])
api_v1.include_router(devices.router, dependencies=[Depends(get_owner)])
api_v1.include_router(files.router, dependencies=[Depends(get_owner)])
api_v1.include_router(sessions.router, dependencies=[Depends(get_owner)])


@api_v1.get("/status", dependencies=[Depends(get_owner)])
async def system_status(container=Depends(get_container)):
    """系统状态（登录后可见：Onboarding 判断是否已配置 LLM）"""
    return container.onboarding.status()
