"""高风险操作确认判定（安全维度）。

职责单一：判断一个工具调用是否需要用户确认。
"""
from __future__ import annotations

from typing import Any


def needs_confirmation(tool, tool_call, confirmed: list[dict[str, Any]]) -> bool:
    """red 级工具且不在已确认列表 → 需要确认。"""
    if tool is None or tool.level != "red":
        return False
    already = any(
        c.get("tool") == tool_call.name and c.get("arguments") == tool_call.arguments
        for c in confirmed
    )
    return not already
