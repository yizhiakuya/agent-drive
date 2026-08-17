"""Agent Drive 后端入口。

职责：创建 Container → 挂载中间件/路由 → 启动。
所有依赖组装在 core/container.py，本文件不做业务。
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

from .api.v1.router import api_v1
from .core.container import Container
from .core.errors import AppError
from .core.logging import AccessLogMiddleware

# 前端构建产物（单服务部署：backend 同时托管 SPA）
_DIST = Path(__file__).resolve().parent.parent.parent / "frontend" / "out"


def _resolve_dist_path(full_path: str) -> Path | None:
    """Resolve a public asset without allowing escape from the frontend build directory."""
    if ".." in full_path.replace("\\", "/").split("/"):
        return None
    try:
        dist_root = _DIST.resolve()
        candidate = (_DIST / full_path).resolve()
        candidate.relative_to(dist_root)
    except (OSError, RuntimeError, ValueError):
        return None
    return candidate


def create_app(container: Container | None = None) -> FastAPI:
    """应用工厂：测试可注入替身 Container。"""
    container = container or Container()

    @asynccontextmanager
    async def lifespan(app_: FastAPI):
        worker_started = False
        if container.settings.task_worker_enabled and container.settings.app_env != "test":
            await container.task_runner.start()
            worker_started = True
        try:
            yield
        finally:
            if worker_started:
                await container.task_runner.stop()
            container.close()

    app = FastAPI(title="Agent Drive", version="0.1.0",
                  description="以 AI 为中心的私人网盘", lifespan=lifespan)
    container.app = app
    app.add_middleware(
        CORSMiddleware,
        allow_origins=container.settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 访问日志中间件放最外层（最后一个 add_middleware = 最先执行）：
    # 真实 IP + 请求 ID + 状态码 + 耗时，统一走 root 日志出口
    app.add_middleware(AccessLogMiddleware)

    # 依赖注入：Container 挂到 app.state
    app.state.container = container

    # 版本化路由
    app.include_router(api_v1)

    # 单服务部署：frontend/out 存在时托管 SPA（静态导出产物）
    if _DIST.exists():

        @app.get("/")
        async def spa_root():
            return FileResponse(_DIST / "index.html")

        @app.get("/{full_path:path}")
        async def spa_fallback(full_path: str):
            if full_path == "api" or full_path.startswith("api/"):
                # API 404 保持 JSON（否则前端会拿到 index.html 解析失败）
                return JSONResponse({"detail": "Not Found"}, status_code=404)
            f = _resolve_dist_path(full_path)
            if f is None:
                return JSONResponse({"detail": "Not Found"}, status_code=404)
            if f.is_file():
                return FileResponse(f)
            return FileResponse(_DIST / "index.html")  # SPA 路由回退
    else:

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
        return JSONResponse(status_code=exc.status_code, content=exc.to_dict())

    return app


app = create_app()
