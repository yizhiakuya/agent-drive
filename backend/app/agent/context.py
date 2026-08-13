"""上下文管理（定义规范原则2：拆分上下文）。

职责单一：token 估算、历史截断、消息组装、工具输出解析。
"""
from __future__ import annotations

import json
from typing import Any


def estimate_tokens(text: str) -> int:
    """粗略 token 估算：中英文混合按 1 字符 ≈ 0.6 token，4 字符保底。"""
    return max(4, int(len(text) * 0.6))


def try_parse_json(text: str):
    """尝试把工具输出解析为结构化对象（前端渲染用）。"""
    try:
        return json.loads(text)
    except Exception:
        return None


def build_history(
    history: list[dict[str, Any]] | None,
    context_budget: int,
) -> list[dict[str, Any]]:
    """按 token 预算从最新历史反向截断。"""
    selected: list[dict[str, Any]] = []
    budget = context_budget
    for h in reversed(history or []):
        if h.get("role") in ("user", "assistant") and h.get("content"):
            cost = estimate_tokens(str(h.get("content", "")))
            if budget - cost < 0 and selected:
                break
            budget -= cost
            selected.append(h)
    return list(reversed(selected))
