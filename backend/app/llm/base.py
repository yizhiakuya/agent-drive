"""LLM Provider 统一抽象：屏蔽三种协议差异，输出统一内部格式。"""
from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass
class ToolSpec:
    """工具描述（JSON Schema 格式的 parameters）

    doc: 给 LLM 看的完整工具文档（按 API 文档标准写）：
         用途 / 参数含义 / 输出格式 / 前置条件 / 错误情况
    """
    name: str
    description: str
    parameters: dict[str, Any] = field(default_factory=dict)
    doc: str = ""


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class LLMResult:
    content: str | None
    tool_calls: list[ToolCall] = field(default_factory=list)
    finish_reason: str = ""
    usage: dict[str, Any] = field(default_factory=dict)


class LLMProvider(Protocol):
    """所有 Provider 必须实现的接口"""

    @property
    def name(self) -> str: ...

    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[ToolSpec] | None = None,
    ) -> LLMResult: ...

    def stream_chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[ToolSpec] | None = None,
    ) -> AsyncIterator[str]:
        """流式生成（异步迭代文本块）。默认退化为非流式。"""
        raise NotImplementedError

    async def test_connection(self) -> dict[str, Any]:
        """返回诊断信息: {ok, model, context_window, supports_tools, latency_ms, error?}"""
        ...

    async def list_models(self) -> list[str]:
        """列出该 Provider 当前可用的模型 ID（去重排序）。"""
        ...
