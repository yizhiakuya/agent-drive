"""OpenAI Chat Completions 协议（兼容 DeepSeek / Ollama / vLLM / Groq 等）"""
from __future__ import annotations

import json
import time
from typing import Any

from openai import AsyncOpenAI

from ...core.retry import with_retry
from ..base import LLMResult, LLMStreamChunk, ToolCall, ToolSpec, normalize_thinking_level


class OpenAICompatProvider:
    def __init__(self, base_url: str, api_key: str, model: str):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self._client = AsyncOpenAI(base_url=self.base_url, api_key=self.api_key)

    @property
    def name(self) -> str:
        return f"openai_compat({self.base_url})"

    async def list_models(self) -> list[str]:
        """GET {base}/models：OpenAI 兼容服务商模型列表（去重排序）。"""
        resp = await self._client.models.list()
        return sorted({m.id for m in resp.data})

    @staticmethod
    def _to_openai_tools(tools: list[ToolSpec]) -> list[dict]:
        return [
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters or {"type": "object", "properties": {}},
                },
            }
            for t in tools
        ]

    @staticmethod
    def _apply_thinking(kwargs: dict[str, Any], thinking_level: str) -> None:
        level = normalize_thinking_level(thinking_level)
        if level != "auto":
            # OpenAI-compatible reasoning models and gateways use this standard field.
            kwargs["reasoning_effort"] = level

    @staticmethod
    def _reasoning_from_message(message: Any) -> str:
        for key in ("reasoning_content", "reasoning", "thinking"):
            value = getattr(message, key, None)
            if isinstance(value, str):
                return value
        return ""

    @staticmethod
    def _convert_messages(messages: list[dict]) -> list[dict]:
        """内部统一格式 → OpenAI chat/completions 格式。"""
        out = []
        for m in messages:
            role = m["role"]
            if role == "tool":
                out.append({
                    "role": "tool",
                    "tool_call_id": m.get("tool_call_id", ""),
                    "content": m.get("content", ""),
                })
            elif role == "assistant" and m.get("tool_calls"):
                msg = {
                    "role": "assistant",
                    "content": m.get("content") or "",
                }
                msg["tool_calls"] = [
                    {
                        "id": tc["id"],
                        "type": "function",
                        "function": {
                            "name": tc["name"],
                            "arguments": json.dumps(tc["arguments"], ensure_ascii=False),
                        },
                    }
                    for tc in m["tool_calls"]
                ]
                out.append(msg)
            else:
                out.append({"role": role, "content": m.get("content", "")})
        return out

    async def chat(self, messages, tools=None, thinking_level="auto") -> LLMResult:
        kwargs: dict[str, Any] = {"model": self.model, "messages": self._convert_messages(messages)}
        self._apply_thinking(kwargs, thinking_level)
        if tools:
            kwargs["tools"] = self._to_openai_tools(tools)
            kwargs["tool_choice"] = "auto"
        resp = await with_retry(lambda: self._client.chat.completions.create(**kwargs))
        msg = resp.choices[0].message
        tool_calls = []
        if getattr(msg, "tool_calls", None):
            for tc in msg.tool_calls:
                try:
                    args = json.loads(tc.function.arguments or "{}")
                except Exception:
                    args = {}
                tool_calls.append(ToolCall(id=tc.id, name=tc.function.name, arguments=args))
        return LLMResult(
            content=msg.content,
            tool_calls=tool_calls,
            finish_reason=resp.choices[0].finish_reason or "",
            usage=resp.usage.model_dump() if resp.usage else {},
            reasoning=self._reasoning_from_message(msg),
        )

    async def stream_chat(self, messages, tools=None, thinking_level="auto"):
        """流式生成正文与 reasoning 片段（不支持工具调用时由调用方保证）。"""
        kwargs: dict[str, Any] = {"model": self.model, "messages": self._convert_messages(messages), "stream": True}
        self._apply_thinking(kwargs, thinking_level)
        if tools:
            kwargs["tools"] = self._to_openai_tools(tools)
        stream = await with_retry(lambda: self._client.chat.completions.create(**kwargs))
        async for chunk in stream:
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if not delta:
                continue
            text = getattr(delta, "content", None) or ""
            reasoning = (
                getattr(delta, "reasoning_content", None)
                or getattr(delta, "reasoning", None)
                or getattr(delta, "thinking", None)
                or ""
            )
            if isinstance(text, str) or isinstance(reasoning, str):
                yield LLMStreamChunk(
                    text=text if isinstance(text, str) else "",
                    reasoning=reasoning if isinstance(reasoning, str) else "",
                )

    async def test_connection(self) -> dict[str, Any]:
        t0 = time.time()
        try:
            resp = await self._client.chat.completions.create(
                model=self.model,
                messages=[{"role": "user", "content": "ping, reply with pong only"}],
                max_tokens=10,
            )
            return {
                "ok": True,
                "model": self.model,
                "reply": (resp.choices[0].message.content or "")[:50],
                "latency_ms": int((time.time() - t0) * 1000),
                "context_window": "unknown",
                "supports_tools": True,
                "error": None,
            }
        except Exception as e:
            return {"ok": False, "model": self.model, "error": str(e), "latency_ms": int((time.time() - t0) * 1000)}
