"""规划器工具：复杂任务先列计划再执行（透明度 + 复杂任务可靠性）。

plan 状态在每次对话的 AgentLoop 实例内（短生命周期），
done 事件把计划带回前端渲染。
"""
from __future__ import annotations

from typing import Any

from ...llm.base import ToolSpec
from .registry import ToolRegistry


def register_plan_tools(reg: ToolRegistry, plan_state: dict[str, Any]) -> None:
    async def set_plan(steps: list[str]) -> dict[str, Any]:
        """设置任务执行计划（复杂任务开工前调用）"""
        plan_state["steps"] = [{"text": s, "status": "pending"} for s in steps]
        return {"plan": plan_state["steps"]}

    reg.register(
        ToolSpec(
            "set_plan",
            "为复杂任务设置分步执行计划（预计 3 步以上时，开工前先调用）",
            {
                "type": "object",
                "properties": {
                    "steps": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "计划步骤列表，每步一句话，按执行顺序",
                    }
                },
                "required": ["steps"],
            },
            doc=(
                "用途：复杂任务先列计划，让用户看到执行路径（透明性）。\n"
                "参数：steps（必填）步骤文本列表，如 [\"扫描网盘文件\", \"按类型分类\", \"移动文件\"]。\n"
                "输出：{plan: [{text, status}]}，初始状态 pending。\n"
                "注意：简单任务（1-2 步）不需要计划。"
            ),
        ),
        set_plan,
        level="green",
        group="plan",
    )

    async def update_plan(index: int, status: str) -> dict[str, Any]:
        """更新计划步骤状态"""
        steps = plan_state.get("steps", [])
        if not steps:
            return {"ok": False, "error": "尚未设置计划，先调用 set_plan"}
        if index < 0 or index >= len(steps):
            return {"ok": False, "error": f"步骤序号越界: {index}（共 {len(steps)} 步）"}
        if status not in ("pending", "in_progress", "done", "skipped", "failed"):
            return {"ok": False, "error": f"无效状态: {status}"}
        steps[index]["status"] = status
        return {"plan": steps}

    reg.register(
        ToolSpec(
            "update_plan",
            "更新计划中某一步的状态",
            {
                "type": "object",
                "properties": {
                    "index": {"type": "integer", "description": "步骤序号（从 0 开始）"},
                    "status": {"type": "string", "enum": ["pending", "in_progress", "done", "skipped", "failed"]},
                },
                "required": ["index", "status"],
            },
            doc=(
                "用途：更新计划步骤状态。完成一步标 done，开始前标 in_progress。\n"
                "参数：index（必填）步骤序号；status（必填）五选一。\n"
                "输出：{plan} 完整计划状态。"
            ),
        ),
        update_plan,
        level="green",
        group="plan",
    )
