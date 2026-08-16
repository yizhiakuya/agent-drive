"""OpenAI Responses API 协议（POST /v1/responses）"""
from __future__ import annotations

import json
import time
from collections.abc import AsyncIterator
from typing import Any

from openai import AsyncOpenAI

from ...core.retry import with_retry
from ..base import LLMResult, ToolCall, ToolSpec


class OpenAIResponsesProvider:
    def __init__(self, base_url: str, api_key: str, model: str):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self._client = AsyncOpenAI(base_url=self.base_url, api_key=self.api_key)

    @property
    def name(self) -> str:
        return f"openai_responses({self.base_url})"

    async def list_models(self) -> list[str]:
        """GET {base}/models：Responses 协议服务商模型列表（去重排序）。"""
        resp = await self._client.models.list()
        return sorted({m.id for m in resp.data})

    def stream_chat(self, messages, tools=None) -> AsyncIterator[str]:
        """暂未实现流式；调用方捕获 NotImplementedError 回退到非流式 chat()。"""
        raise NotImplementedError

    @staticmethod
    def _to_responses_tools(tools: list[ToolSpec]) -> list[dict]:
        return [
            {
                "type": "function",
                "name": t.name,
                "description": t.description,
                "parameters": t.parameters or {"type": "object", "properties": {}},
            }
            for t in tools
        ]

    async def chat(self, messages, tools=None) -> LLMResult:
        kwargs: dict[str, Any] = {
            "model": self.model,
            "input": self._convert_messages(messages),
        }
        if tools:
            kwargs["tools"] = self._to_responses_tools(tools)
        resp = await with_retry(lambda: self._client.responses.create(**kwargs))

        content_parts = []
        tool_calls = []
        for item in getattr(resp, "output", []) or []:
            if item.type == "message":
                for c in item.content:
                    if getattr(c, "type", "") == "output_text":
                        content_parts.append(c.text)
            elif item.type == "function_call":
                try:
                    args = json.loads(item.arguments or "{}")
                except Exception:
                    args = {}
                tool_calls.append(ToolCall(id=item.call_id or item.id, name=item.name, arguments=args))
        return LLMResult(
            content="\n".join(content_parts) or None,
            tool_calls=tool_calls,
            finish_reason=getattr(resp, "status", "") or "",
        )

    @staticmethod
    def _convert_messages(messages: list[dict]) -> list[dict]:
        """把统一消息格式转为 Responses input 格式。"""
        out = []
        for m in messages:
            role = m["role"]
            if role == "tool":
                out.append({
                    "type": "function_call_output",
                    "call_id": m.get("tool_call_id", ""),
                    "output": json.dumps(m.get("content", ""), ensure_ascii=False),
                })
            elif role == "assistant" and m.get("tool_calls"):
                for tc in m["tool_calls"]:
                    out.append({
                        "type": "function_call",
                        "call_id": tc["id"],
                        "name": tc["name"],
                        "arguments": json.dumps(tc["arguments"], ensure_ascii=False),
                    })
                if m.get("content"):
                    out.append({"role": "assistant", "content": m["content"]})
            elif role == "system":
                out.append({"role": "system", "content": [{"type": "input_text", "text": m["content"]}]})
            else:
                out.append({"role": role, "content": [{"type": "input_text", "text": m.get("content", "")}]})
        return out

    async def test_connection(self) -> dict[str, Any]:
        t0 = time.time()
        try:
            resp = await self._client.responses.create(
                model=self.model,
                input="ping, reply with pong only",
            )
            text = ""
            for item in getattr(resp, "output", []) or []:
                if item.type == "message":
                    for c in item.content:
                        if getattr(c, "type", "") == "output_text":
                            text += c.text
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
