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


def _mask(key: str) -> str:
    """只显前缀：绝不回显尾部字符（尾部可能泄露密钥特征）。"""
    if not key:
        return ""
    return key[:6] + "…" if len(key) > 6 else "…"


@router.get("")
async def get_config(container=Depends(get_container)):
    """设置页回显：LLM + embeddings 当前配置（api_key 掩码）"""
    cfg = container.llm.load()
    base = container.onboarding.status()
    if cfg is not None:
        base["llm"] = {
            "type": cfg.type,
            "base_url": cfg.base_url,
            "model": cfg.model,
            "temperature": cfg.temperature,
            "api_key_masked": _mask(cfg.api_key),
        }
        if cfg.embeddings is not None:
            base["embeddings"] = {
                "provider": cfg.embeddings.provider,
                "base_url": cfg.embeddings.base_url,
                "model": cfg.embeddings.model,
                "api_key_masked": _mask(cfg.embeddings.api_key),
            }
        else:
            base["embeddings"] = None
    return base


@router.put("/embeddings")
async def save_embeddings(body: dict, container=Depends(get_container)):
    """保存 embeddings 配置（云 API，如 Jina）。key 为空表示沿用现有。"""
    cfg = container.llm.load()
    if cfg is None:
        from fastapi import HTTPException
        raise HTTPException(400, "LLM 未配置，请先完成 Onboarding")
    from ...llm.manager import EmbeddingConfig
    incoming_key = str(body.get("api_key") or "")
    key = incoming_key or (cfg.embeddings.api_key if cfg.embeddings else "")
    new_emb = EmbeddingConfig(
        provider=str(body.get("provider") or "jina"),
        base_url=str(body.get("base_url") or "https://api.jina.ai/v1"),
        api_key=key,
        model=str(body.get("model") or "jina-embeddings-v3"),
    )
    cfg.embeddings = new_emb
    container.llm.save(cfg)
    # 测试连接（不阻塞保存）
    diag = await container.llm.get_embedding_provider().test_connection()
    return {"ok": True, "saved": {"provider": new_emb.provider, "model": new_emb.model}, "test": diag}
