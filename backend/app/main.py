"""Agent Drive 后端入口。

职责：创建 Container → 挂载中间件/路由 → 启动。
所有依赖组装在 core/container.py，本文件不做业务。
"""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.v1.router import api_v1
from .core.container import Container
from .core.errors import AppError


def create_app(container: Container | None = None) -> FastAPI:
    """应用工厂：测试可注入替身 Container。"""
    container = container or Container()

    app = FastAPI(title="Agent Drive", version="0.1.0",
                  description="以 AI 为中心的私人网盘")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=container.settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 依赖注入：Container 挂到 app.state
    app.state.container = container

    # 版本化路由
    app.include_router(api_v1)

    @app.get("/")
    async def root():
        return {
            "name": "Agent Drive",
            "status": "configured" if container.llm.is_configured() else "needs_setup",
            "api": "/api/v1",
            "docs": "/docs",
        }

    @app.exception_handler(AppError)
    async def app_error_handler(request, exc: AppError):
        from fastapi.responses import JSONResponse
        return JSONResponse(status_code=exc.status_code, content=exc.to_dict())

    return app


app = create_app()
