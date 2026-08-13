"""v1 配置路由（Onboarding 用）"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from ...llm.manager import LLMConfig
from ...schemas.config import LLMConfigIn
from ..deps import get_container

router = APIRouter(prefix="/config", tags=["config"])


@router.get("/status")
async def status(container=Depends(get_container)):
    return container.onboarding.status()


@router.post("")
async def configure(cfg: LLMConfigIn, container=Depends(get_container)):
    return await container.onboarding.configure(cfg.type, cfg.base_url, cfg.api_key, cfg.model)


@router.post("/test")
async def test(cfg: LLMConfigIn, container=Depends(get_container)):
    return await container.llm.test(LLMConfig(**cfg.model_dump()))
