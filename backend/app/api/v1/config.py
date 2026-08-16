"""v1 配置路由（Onboarding 用）"""
from __future__ import annotations

import asyncio
from typing import cast

from fastapi import APIRouter, Depends, HTTPException

from ...llm.manager import LLMConfig, ProviderType
from ...schemas.config import LLMConfigIn, LLMModelsIn
from ..deps import get_container

router = APIRouter(prefix="/config", tags=["config"])


@router.get("/status")
async def status(container=Depends(get_container)):
    return container.onboarding.status()


@router.post("")
async def configure(cfg: LLMConfigIn, container=Depends(get_container)):
    """保存 LLM 配置。api_key 留空沿用已存 key（仅当 type/base_url 与已存一致）。"""
    key = cfg.api_key
    if not key:
        stored = container.llm.load()
        if (
            stored
            and stored.api_key
            and stored.type == cfg.type
            and stored.base_url.rstrip("/") == cfg.base_url.rstrip("/")
        ):
            key = stored.api_key
        if not key:
            return {
                "ok": False,
                "error": "API Key 为空：修改协议/接口地址后请重新填写（未改则留空沿用已保存的 Key）",
            }
    return await container.onboarding.configure(cfg.type, cfg.base_url, key, cfg.model)


@router.post("/test")
async def test(cfg: LLMConfigIn, container=Depends(get_container)):
    return await container.llm.test(LLMConfig(**cfg.model_dump()))


@router.post("/models")
async def list_models(cfg: LLMModelsIn, container=Depends(get_container)):
    """按表单值获取 Provider 可用模型列表（只读探测，不落盘）。

    api_key 留空时回退已保存配置的 key，但仅当表单 type/base_url 与已存配置一致——
    避免把已存 key 发给用户新填的陌生地址。
    """
    if cfg.type not in {"openai_compat", "openai_responses", "anthropic"}:
        return {"ok": False, "error": f"未知 Provider 类型: {cfg.type}"}
    key = cfg.api_key
    if not key:
        stored = container.llm.load()
        if (
            stored
            and stored.api_key
            and stored.type == cfg.type
            and stored.base_url.rstrip("/") == cfg.base_url.rstrip("/")
        ):
            key = stored.api_key
        if not key:
            return {"ok": False, "error": "API Key 为空：请先填写（或先保存当前配置再获取）"}
    llm_cfg = LLMConfig(
        type=cast(ProviderType, cfg.type),
        base_url=cfg.base_url,
        api_key=key,
        model="__models__",  # 探测用占位，不参与请求
    )
    try:
        models = await asyncio.wait_for(container.llm.list_models(llm_cfg), timeout=20)
        return {"ok": True, "models": models, "type": cfg.type}
    except asyncio.TimeoutError:
        return {"ok": False, "error": "获取模型列表超时（20s），请检查接口地址与网络"}
    except Exception as e:  # 探测端点：错误语义化返回给设置页，不落 500
        hint = ""
        if cfg.type in ("openai_compat", "openai_responses"):
            hint = "（提示：非 OpenAI 标准 /models 格式的服务如 Ollama，请手动填写模型名）"
        return {"ok": False, "error": f"{e}{hint}"}


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
        raise HTTPException(400, "LLM 未配置，请先完成 Onboarding")
    from ...llm.manager import EmbeddingConfig
    incoming_key = str(body.get("api_key") or "")
    key = incoming_key or (cfg.embeddings.api_key if cfg.embeddings else "")
    provider = str(body.get("provider") or "jina")
    if provider != "jina":
        raise HTTPException(400, "当前仅支持 Jina embedding provider")
    if not key:
        raise HTTPException(400, "Embedding API Key 不能为空")
    new_emb = EmbeddingConfig(
        provider=provider,
        base_url=str(body.get("base_url") or "https://api.jina.ai/v1"),
        api_key=key,
        model=str(body.get("model") or "jina-embeddings-v3"),
    )
    cfg.embeddings = new_emb
    container.llm.save(cfg)
    # 测试连接（不阻塞保存）
    diag = await container.llm.get_embedding_provider().test_connection()
    task = None
    if diag.get("ok"):
        container.tasks.refresh_embedder()
        job, _ = container.tasks.enqueue_rebuild(force=True, origin="embedding.config")
        task = job.to_dict()
    return {
        "ok": True,
        "saved": {"provider": new_emb.provider, "model": new_emb.model},
        "test": diag,
        "rebuild_task": task,
    }
