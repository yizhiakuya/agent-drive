"""API 依赖：从 app.state 获取 Container 的服务 + 统一鉴权。"""
from __future__ import annotations

from fastapi import HTTPException, Request

from ..auth.store import SESSION_COOKIE
from ..core.container import Container


def get_container(request: Request) -> Container:
    return request.app.state.container


_QUERY_TOKEN_PATHS = frozenset({"/api/v1/files/raw", "/api/v1/files/download"})


def get_owner(request: Request) -> None:
    """统一鉴权：Cookie/Bearer 全站；设备查询令牌只限媒体读取端点。

    媒体元素无法附加 Authorization Header，因此原生 App 的 raw/download GET
    保留 ``?token=`` 兼容通道；其他路径即便携带合法设备令牌也不得借 URL 放行。
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
    if (
        request.method == "GET"
        and request.url.path in _QUERY_TOKEN_PATHS
        and query_token
        and auth.verify_device_token(query_token)
    ):
        return
    raise HTTPException(401, "未登录")
