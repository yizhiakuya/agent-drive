"""记忆工具：remember / memory_search / memory_get（OpenClaw 式检索）。

- remember: Agent 主动记录持久事实到 MEMORY.md（策划层）
- memory_search: 全文搜索长期记忆 + 每日笔记（工作层按需检索）
- memory_get: 读取具体记忆文件
"""
from __future__ import annotations

from typing import Any

from ...llm.base import ToolSpec
from .registry import ToolRegistry


def register_memory_tools(reg: ToolRegistry, memory) -> None:
    async def remember(content: str) -> dict[str, Any]:
        return memory.remember(content)

    reg.register(
        ToolSpec(
            "remember",
            "记录持久事实/决策到长期记忆（MEMORY.md）",
            {"type": "object", "properties": {
                "content": {"type": "string", "description": "要记住的事实/决策，一句话"},
            }, "required": ["content"]},
            doc=(
                "用途：记住重要且持久的信息（用户背景、项目决策、偏好、约定）。\n"
                "参数：content（必填）一句话事实。\n"
                "输出：{saved, memory, entry}。\n"
                "何时用：用户说'记住X'/表达重要事实/做出决策/约定事项时。\n"
                "注意：临时信息不要记；已有相似记忆时用新内容 supersede。"
            ),
        ),
        remember,
        level="yellow",
        group="memory",
    )

    async def memory_search(query: str) -> list[dict[str, Any]]:
        return memory.search_memory(query)

    reg.register(
        ToolSpec(
            "memory_search",
            "搜索长期记忆和每日笔记（按需检索，不占上下文）",
            {"type": "object", "properties": {
                "query": {"type": "string", "description": "搜索关键词"},
            }, "required": ["query"]},
            doc=(
                "用途：在 MEMORY.md 和 memory/*.md 中全文搜索相关记忆。\n"
                "参数：query（必填）关键词。\n"
                "输出：最多 10 条 [{file, line}]。\n"
                "何时用：不确定是否记得某事/需要回忆细节时先搜索。"
            ),
        ),
        memory_search,
        group="memory",
    )

    async def memory_get(name: str) -> str:
        return memory.get_memory_file(name)

    reg.register(
        ToolSpec(
            "memory_get",
            "读取指定记忆文件内容",
            {"type": "object", "properties": {
                "name": {"type": "string", "description": "文件名，如 MEMORY.md 或 2026-08-13.md"},
            }, "required": ["name"]},
            doc=(
                "用途：读取具体记忆文件的完整内容。\n"
                "参数：name（必填）文件名。\n"
                "输出：文件内容（最多 4000 字符）。"
            ),
        ),
        memory_get,
        group="memory",
    )
