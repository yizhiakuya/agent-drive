"""LLM 模型列表：Manager 装配与 Provider 实现（pytest 风格）。"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

from app.llm.manager import LLMConfig, LLMManager


class _FakeProvider:
    def __init__(self, models: list[str]) -> None:
        self._models = models

    async def list_models(self) -> list[str]:
        return self._models


def test_manager_list_models_builds_and_delegates(monkeypatch) -> None:
    mgr = LLMManager("/nonexistent/agent-config.json")
    built: dict = {}

    def fake_build(cfg):
        built["cfg"] = cfg
        return _FakeProvider(["m2", "m1"])

    monkeypatch.setattr(LLMManager, "build", staticmethod(fake_build))
    cfg = LLMConfig(type="openai_compat", base_url="http://x", api_key="k", model="m")
    out = asyncio.run(mgr.list_models(cfg))
    assert out == ["m2", "m1"]
    assert built["cfg"] is cfg


def test_openai_compat_list_models_dedup_sorted() -> None:
    from app.llm.providers.openai_compat import OpenAICompatProvider

    p = OpenAICompatProvider("http://x", "k", "m")

    class _Models:
        async def list(self):
            return SimpleNamespace(
                data=[
                    SimpleNamespace(id="b"),
                    SimpleNamespace(id="a"),
                    SimpleNamespace(id="b"),
                ]
            )

    p._client = SimpleNamespace(models=_Models())  # 替换 SDK 客户端，纯逻辑测试
    assert asyncio.run(p.list_models()) == ["a", "b"]


def test_anthropic_list_models_passes_limit() -> None:
    from app.llm.providers.anthropic import AnthropicProvider

    p = AnthropicProvider("http://x", "k", "m")
    seen: dict = {}

    class _Models:
        async def list(self, limit):
            seen["limit"] = limit
            return SimpleNamespace(
                data=[SimpleNamespace(id="claude-1"), SimpleNamespace(id="claude-2")]
            )

    p._client = SimpleNamespace(models=_Models())
    assert asyncio.run(p.list_models()) == ["claude-1", "claude-2"]
    assert seen["limit"] == 1000
