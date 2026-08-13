"""Anthropic Messages 协议（POST /v1/messages，Claude 及兼容服务）"""
from __future__ import annotations

import json
import time
from typing import Any

import anthropic

from ..base import LLMResult, ToolCall, ToolSpec


class AnthropicProvider:
    def __init__(self, base_url: str, api_key: str, model: str):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self._client = anthropic.AsyncAnthropic(api_key=api_key, base_url=base_url)

    @property
    def name(self) -> str:
        return f"anthropic({self.base_url})"

    @staticmethod
    def _to_anthropic_tools(tools: list[ToolSpec]) -> list[dict]:
        return [
            {
                "name": t.name,
                "description": t.description,
                "input_schema": t.parameters or {"type": "object", "properties": {}},
            }
            for t in tools
        ]

    @staticmethod
    def _convert_messages(messages: list[dict]) -> list[dict]:
        """统一格式 → Anthropic 格式。"""
        out = []
        for m in messages:
            role = m["role"]
            if role == "system":
                continue  # system 单独处理
            if role == "tool":
                out.append({
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": m.get("tool_call_id", ""),
                            "content": json.dumps(m.get("content", ""), ensure_ascii=False)[:50000],
                        }
                    ],
                })
            elif role == "assistant" and m.get("tool_calls"):
                block = []
                if m.get("content"):
                    block.append({"type": "text", "text": m["content"]})
                for tc in m["tool_calls"]:
                    block.append({
                        "type": "tool_use",
                        "id": tc["id"],
                        "name": tc["name"],
                        "input": tc["arguments"],
                    })
                out.append({"role": "assistant", "content": block})
            else:
                out.append({"role": "user", "content": m.get("content", "")})
        return out

    async def chat(self, messages, tools=None) -> LLMResult:
        system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
        kwargs: dict[str, Any] = {
            "model": self.model,
            "max_tokens": 8192,
            "messages": self._convert_messages(messages),
        }
        if system:
            kwargs["system"] = system
        if tools:
            kwargs["tools"] = self._to_anthropic_tools(tools)

        resp = await self._client.messages.create(**kwargs)

        content_text = []
        tool_calls = []
        for block in resp.content:
            if block.type == "text":
                content_text.append(block.text)
            elif block.type == "tool_use":
                tool_calls.append(ToolCall(id=block.id, name=block.name, arguments=block.input or {}))
        return LLMResult(
            content="\n".join(content_text) or None,
            tool_calls=tool_calls,
            finish_reason=resp.stop_reason or "",
        )

    async def test_connection(self) -> dict[str, Any]:
        t0 = time.time()
        try:
            resp = await self._client.messages.create(
                model=self.model,
                max_tokens=16,
                messages=[{"role": "user", "content": "ping, reply with pong only"}],
            )
            text = "".join(b.text for b in resp.content if b.type == "text")
            return {
                "ok": True,
                "model": self.model,
                "reply": text[:50],
                "latency_ms": int((time.time() - t0) * 1000),
                "context_window": "unknown",
                "supports_tools": True,
                "error": None,
            }
        except Exception as e:
            return {"ok": False, "model": self.model, "error": str(e), "latency_ms": int((time.time() - t0) * 1000)}
