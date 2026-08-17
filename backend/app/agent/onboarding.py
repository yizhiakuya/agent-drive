"""Onboarding 引导流程：Agent 驱动首次配置。

流程：前端收集 LLM 配置 → 保存 → Agent 自检 → 设置默认偏好 → 就绪。
之后所有配置修改都走对话（backend_api 发现并调用配置接口）。
"""
from __future__ import annotations

from ..llm.manager import LLMConfig, LLMManager


class Onboarding:
    def __init__(self, llm: LLMManager, memory):
        self.llm = llm
        self.memory = memory

    def status(self) -> dict:
        cfg = self.llm.load()
        configured = self.llm.is_configured()
        return {
            "configured": configured,
            "provider_types": {
                "openai_compat": "OpenAI 兼容 (DeepSeek/Ollama/vLLM/Groq...)",
                "openai_responses": "OpenAI Responses",
                "anthropic": "Anthropic (Claude 及兼容)",
            },
            "current": {
                "type": cfg.type, "base_url": cfg.base_url, "model": cfg.model
            } if cfg else None,
            "preferences": self.memory.all(),
        }

    async def configure(self, type: str, base_url: str, api_key: str, model: str) -> dict:
        """首次配置：测试连接 → 保存 → 设默认偏好"""
        from typing import cast

        from ..llm.manager import ProviderType
        cfg = LLMConfig(type=cast(ProviderType, type), base_url=base_url, api_key=api_key, model=model)
        diag = await self.llm.test(cfg)
        if not diag.get("ok"):
            return {"ok": False, "test": diag, "message": "连接测试失败"}
        self.llm.save(cfg)
        # 默认偏好
        if not self.memory.get("language"):
            self.memory.set("language", "zh")
        if not self.memory.get("agent_name"):
            self.memory.set("agent_name", "File Concierge")
        return {
            "ok": True,
            "test": diag,
            "message": "配置成功！Agent 已就绪。你现在可以直接用自然语言指挥它了。",
        }
