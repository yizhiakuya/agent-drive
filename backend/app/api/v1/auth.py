"""v1 认证路由：设密 / 登录 / 登出 / 设备令牌 / 状态。"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

from ...auth.store import SESSION_COOKIE, SESSION_TTL_SECONDS
from ..deps import get_container, get_owner

router = APIRouter(prefix="/auth", tags=["auth"])


class PasswordBody(BaseModel):
    password: str = Field(min_length=1, max_length=128)


class DeviceTokenBody(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    name: str = ""


def _set_session_cookie(response: Response, token: str, secure: bool) -> None:
    response.set_cookie(
        SESSION_COOKIE, token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="lax",
        secure=secure,
        path="/",
    )


def _client_ip(request: Request) -> str:
    # nginx 反代：优先取 X-Forwarded-For 首段
    fwd = request.headers.get("x-forwarded-for")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


@router.get("/status")
async def auth_status(container=Depends(get_container)):
    """是否已设置密码（公开：登录页需要知道进登录还是设密）。"""
    return {"initialized": container.auth.is_initialized()}


@router.post("/setup")
async def setup_password(payload: PasswordBody, request: Request, response: Response, container=Depends(get_container)):
    """首次设置密码（第一个设置者成为主人），成功后直接登录。"""
    if not container.auth.check_rate("setup:" + _client_ip(request)):
        raise HTTPException(429, "尝试过于频繁，请稍后再试")
    try:
        container.auth.setup(payload.password)
    except ValueError as e:
        raise HTTPException(400, str(e))
    token = container.auth.issue_session()
    _set_session_cookie(response, token, secure=container.settings.app_env == "prod")
    container.audit.record("auth.setup")
    # session 同时放响应体：App WebView 跨域拿不到 Cookie，用它换设备令牌
    return {"ok": True, "session": token}


@router.post("/login")
async def login(payload: PasswordBody, request: Request, response: Response, container=Depends(get_container)):
    if not container.auth.is_initialized():
        raise HTTPException(400, "尚未设置密码")
    if not container.auth.check_rate("login:" + _client_ip(request)):
        raise HTTPException(429, "尝试过于频繁，请稍后再试")
    if not container.auth.verify_password(payload.password):
        container.audit.record("auth.login_failed", "ip=" + _client_ip(request))
        raise HTTPException(401, "密码错误")
    token = container.auth.issue_session()
    _set_session_cookie(response, token, secure=container.settings.app_env == "prod")
    container.audit.record("auth.login", "ip=" + _client_ip(request))
    return {"ok": True, "session": token}


@router.post("/logout")
async def logout(response: Response):
    response.delete_cookie(SESSION_COOKIE, path="/")
    return {"ok": True}


@router.post("/device-token")
async def issue_device_token(payload: DeviceTokenBody, container=Depends(get_container), _=Depends(get_owner)):
    """登录后颁发设备令牌（明文只返回一次，App 存本地供后台同步用）。"""
    token = container.auth.issue_device_token(payload.device_id, payload.name)
    container.audit.record("auth.device_token", "device=" + payload.device_id)
    return {"token": token, "device_id": payload.device_id}


@router.get("/me")
async def me(_=Depends(get_owner)):
    return {"authed": True}