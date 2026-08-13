"""对话相关数据模型"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    message: str
    history: list[dict[str, Any]] = []
    confirmations: list[dict[str, Any]] = Field(
        default_factory=list,
        description="用户已确认的高风险操作 [{tool, arguments}]",
    )
    session_id: str | None = None


class ChatResponse(BaseModel):
    reply: str
    tool_trace: list[dict[str, Any]] = []
    steps: int = 0
    latency_ms: int = 0
    pending_confirmation: dict[str, Any] | None = None
    session_id: str | None = None
    needs_summary: bool = False
    routed: str | None = None  # chat(轻量) | task(完整loop)
