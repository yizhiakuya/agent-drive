"""v1 认证路由：设密 / 登录 / 登出 / 设备令牌 / 状态。"""
from __future__ import annotations

import ipaddress

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


class PairExchangeBody(BaseModel):
    code: str = Field(min_length=8, max_length=128)
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
    """只信任从 loopback 反代进来的 X-Forwarded-For，直连请求不能伪造限速 key。"""
    peer = request.client.host if request.client else "unknown"
    trusted_proxy = False
    try:
        trusted_proxy = ipaddress.ip_address(peer).is_loopback
    except ValueError:
        pass
    fwd = request.headers.get("x-forwarded-for")
    if trusted_proxy and fwd:
        # nginx 的 $proxy_add_x_forwarded_for 会保留客户端自带值并把真实对端追加在末尾；
        # 因此只取最右侧地址，不能取可由公网请求伪造的第一个值。
        candidate = fwd.rsplit(",", 1)[-1].strip()
        try:
            return str(ipaddress.ip_address(candidate))
        except ValueError:
            pass
    return peer


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
async def logout(request: Request, response: Response, container=Depends(get_container)):
    """退出当前凭据：删除 Cookie，并在服务端吊销当前 session 或设备令牌。"""
    revoked = False
    session = request.cookies.get(SESSION_COOKIE)
    if session:
        revoked = container.auth.revoke_session(session) or revoked
    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        bearer = header[7:].strip()
        revoked = container.auth.revoke_session(bearer) or container.auth.revoke_device_token(bearer) or revoked
    response.delete_cookie(SESSION_COOKIE, path="/")
    container.audit.record("auth.logout", "revoked=" + str(revoked).lower())
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


@router.post("/pairing")
async def issue_pairing(container=Depends(get_container), _=Depends(get_owner)):
    """已登录 web 生成配对码（二维码携带，5 分钟一次性）。"""
    info = container.auth.issue_pairing()
    container.audit.record("auth.pairing_issue")
    return info


@router.post("/pair-exchange")
async def pair_exchange(payload: PairExchangeBody, request: Request,
                        container=Depends(get_container)):
    """App 扫码兑换：配对码 → 长期设备令牌（免密码）。"""
    if not container.auth.check_rate("pair:" + _client_ip(request), limit=10):
        raise HTTPException(429, "尝试过于频繁，请稍后再试")
    try:
        token = container.auth.exchange_pairing(payload.code, payload.device_id, payload.name)
    except ValueError as e:
        container.audit.record("auth.pair_exchange_failed", "ip=" + _client_ip(request) + " reason=" + str(e))
        raise HTTPException(400, str(e))
    container.audit.record("auth.pair_exchange", "device=" + payload.device_id)
    return {"token": token, "device_id": payload.device_id}