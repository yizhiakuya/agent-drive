"""LLM 配置管理与 Provider 工厂。

配置存于 system/agent-config.json —— 这是 agent 自管理的核心：
agent 自己可以读写这份配置（通过 system_tools.set_llm_provider）。
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel

from .base import LLMProvider
from .providers.anthropic import AnthropicProvider
from .providers.openai_compat import OpenAICompatProvider
from .providers.responses import OpenAIResponsesProvider

ProviderType = Literal["openai_compat", "openai_responses", "anthropic"]

PROVIDER_LABELS = {
    "openai_compat": "OpenAI 兼容 (chat/completions) — DeepSeek/Ollama/vLLM/Groq...",
    "openai_responses": "OpenAI Responses (responses) — 官方新协议",
    "anthropic": "Anthropic (messages) — Claude 及兼容服务",
}


class EmbeddingConfig(BaseModel):
    """向量化配置（M2b 语义搜索，云 API）"""
    provider: str = "jina"
    base_url: str = "https://api.jina.ai/v1"
    api_key: str = ""
    model: str = "jina-embeddings-v3"


class LLMConfig(BaseModel):
    type: ProviderType
    base_url: str
    api_key: str = ""
    model: str
    embeddings: EmbeddingConfig | None = None  # 可选：语义搜索向量化配置
    # 注：不存 temperature——模型请求不带温度参数（按各服务商默认），设置页亦无此输入


class LLMManager:
    def __init__(self, config_path: Path | str):
        self.config_path = Path(config_path)

    # ---------- 配置读写 ----------
    def load(self) -> LLMConfig | None:
        if not self.config_path.exists():
            return None
        try:
            data = json.loads(self.config_path.read_text(encoding="utf-8"))
            return LLMConfig(**data)
        except Exception:
            return None

    def save(self, cfg: LLMConfig) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(
            json.dumps(cfg.model_dump(), indent=2, ensure_ascii=False), encoding="utf-8"
        )
        try:
            os.chmod(self.config_path, 0o600)  # API Key 明文：仅 owner 可读
        except OSError:
            pass

    def is_configured(self) -> bool:
        cfg = self.load()
        return cfg is not None and bool(cfg.api_key) and bool(cfg.model)

    # ---------- Provider 工厂 ----------
    def get_provider(self) -> LLMProvider:
        cfg = self.load()
        if cfg is None:
            from ..core.errors import ConfigError
            raise ConfigError("LLM 未配置，请先完成 Onboarding")
        return self.build(cfg)

    def get_embedding_provider(self):
        """获取向量化 Provider（未配置时返回 None）"""
        cfg = self.load()
        if cfg is None or cfg.embeddings is None or not cfg.embeddings.api_key:
            return None
        from .embeddings import JinaEmbeddingProvider
        e = cfg.embeddings
        if e.provider == "jina":
            return JinaEmbeddingProvider(e.base_url, e.api_key, e.model)
        raise ValueError(f"未知 embedding provider: {e.provider}")

    @staticmethod
    def build(cfg: LLMConfig) -> LLMProvider:
        if cfg.type == "openai_compat":
            return OpenAICompatProvider(cfg.base_url, cfg.api_key, cfg.model)
        if cfg.type == "openai_responses":
            return OpenAIResponsesProvider(cfg.base_url, cfg.api_key, cfg.model)
        if cfg.type == "anthropic":
            return AnthropicProvider(cfg.base_url, cfg.api_key, cfg.model)
        raise ValueError(f"未知 Provider 类型: {cfg.type}")

    async def list_models(self, cfg: LLMConfig) -> list[str]:
        """按给定配置列出 Provider 可用模型 ID（只读探测，不落盘）。"""
        provider = self.build(cfg)
        return await provider.list_models()

    async def test(self, cfg: LLMConfig) -> dict[str, Any]:
        try:
            provider = self.build(cfg)
            diag = await provider.test_connection()
            diag["type"] = cfg.type
            return diag
        except Exception as e:
            return {"ok": False, "error": str(e), "type": cfg.type}
