"""API 依赖：从 app.state 获取 Container 的服务。"""
from __future__ import annotations

from fastapi import Request

from ..core.container import Container


def get_container(request: Request) -> Container:
    return request.app.state.container
