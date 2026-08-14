"""API 依赖：从 app.state 获取 Container 的服务 + 统一鉴权。"""
from __future__ import annotations

from fastapi import HTTPException, Request

from ..auth.store import SESSION_COOKIE
from ..core.container import Container


def get_container(request: Request) -> Container:
    return request.app.state.container


def get_owner(request: Request) -> None:
    """统一鉴权：会话 Cookie / Bearer 设备令牌 / 媒体查询参数 token，任一有效即放行。

    三种通道的原因：
    - Cookie：web/PWA 同源请求（含 SSE、上传、分享）
    - Bearer：App 原生后台（相册同步 Worker、心跳）
    - ?token=：媒体元素（img/video/audio）无法带 Cookie/Header 的兼容通道
    """
    auth = request.app.state.container.auth
    token = request.cookies.get(SESSION_COOKIE)
    if token and auth.verify_session(token):
        return
    header = request.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        bearer = header[7:].strip()
        if auth.verify_session(bearer) or auth.verify_device_token(bearer):
            return
    query_token = request.query_params.get("token")
    if query_token and auth.verify_device_token(query_token):
        return
    raise HTTPException(401, "未登录")
