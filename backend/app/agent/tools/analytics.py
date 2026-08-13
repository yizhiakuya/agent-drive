"""错误分析工具：meta-agent 模式 —— 用 LLM 分析失败日志，诊断系统根因。

对应定义规范原则5（LLM 驱动错误分析）：
失败日志喂给 meta-agent → 根因分类 → 改进建议 → 反哺系统提示/工具。
"""
from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from ...llm.base import LLMProvider, ToolSpec
from .registry import ToolRegistry

# 错误分类框架（可预测性：失败模式固定可分类）
ERROR_CATEGORIES = {
    "tool_missing": "工具缺失或不可用",
    "param_error": "参数错误（类型/缺失/越界）",
    "permission": "权限不足或路径越界",
    "llm_error": "LLM 调用失败（超时/协议/配额）",
    "validation": "Critic 验证失败（操作未生效）",
    "user_cancel": "用户取消或拒绝",
    "unknown": "未知错误",
}


def _categorize(error_text: str) -> str:
    e = error_text.lower()
    if any(k in e for k in ("越界", "permission", "not allowed", "forbidden")):
        return "permission"
    if any(k in e for k in ("not found", "不存在", "filenotfound")):
        return "param_error"
    if any(k in e for k in ("timeout", "connection", "401", "403", "429", "apierror", "protocol")):
        return "llm_error"
    if any(k in e for k in ("验证失败", "validation")):
        return "validation"
    if "工具不存在" in error_text:
        return "tool_missing"
    return "unknown"


def register_analytics_tools(reg: ToolRegistry, llm_provider: Callable[[], LLMProvider], audit_log_path, sessions=None) -> None:
    async def analyze_failures(recent: int = 50) -> dict[str, Any]:
        """读取最近审计日志 + 会话错误，用 LLM 诊断根因。"""
        # 1. 收集失败事件（audit 可能是 AuditLogger 对象或 Path，duck-typing）
        failures = []
        if audit_log_path is not None and hasattr(audit_log_path, "failures"):
            failures = audit_log_path.failures(recent)
        elif audit_log_path is not None and hasattr(audit_log_path, "exists") and audit_log_path.exists():
            for line in audit_log_path.read_text().splitlines()[-recent:]:
                try:
                    ev = json.loads(line)
                    ev_text = json.dumps(ev, ensure_ascii=False)
                    if any(k in ev_text for k in ("error", "pending-confirm", "fail")):
                        failures.append(ev)
                except Exception:
                    continue

        # 2. 启发式分类（不依赖 LLM 的快速统计）
        by_category: dict[str, int] = {}
        samples: list[str] = []
        for f in failures:
            text = json.dumps(f, ensure_ascii=False)
            cat = _categorize(text)
            by_category[cat] = by_category.get(cat, 0) + 1
            if len(samples) < 8:
                samples.append(text[:200])

        stats = {
            "total_events_scanned": len(failures),
            "by_category": {ERROR_CATEGORIES.get(k, k): v for k, v in by_category.items()},
        }

        # 3. LLM 深度分析（meta-agent）
        if not samples:
            return {**stats, "analysis": "无失败事件，系统运行正常 🎉", "recommendations": []}
        try:
            provider = llm_provider()
            prompt = (
                "你是 Agent Drive 的可靠性分析专家。分析以下失败事件，输出：\n"
                "1) 根因判断（1-2句）\n"
                "2) 3条以内可执行的改进建议（针对系统提示/工具设计/上下文管理）\n"
                f"分类统计: {json.dumps(stats, ensure_ascii=False)}\n"
                f"事件样本:\n" + "\n".join(samples)
            )
            result = await provider.chat([{"role": "user", "content": prompt}])
            return {**stats, "analysis": (result.content or "")[:1500], "recommendations": []}
        except Exception as e:
            return {**stats, "analysis": f"(LLM 分析不可用: {e})", "recommendations": []}

    reg.register(
        ToolSpec(
            "analyze_failures",
            "分析系统最近的操作失败事件，诊断根因并给出改进建议",
            {"type": "object", "properties": {"recent": {"type": "integer", "description": "扫描最近多少条审计事件"}}},
            doc=(
                "用途：可靠性诊断。读取审计日志中的失败事件（工具错误/待确认/异常），\n"
                "先用规则分类统计，再用 LLM 深度分析根因和改进建议。\n"
                "参数：recent（可选，默认50）扫描条数。\n"
                "输出：{total_events_scanned, by_category, analysis}。"
            ),
        ),
        analyze_failures,
        group="analytics",
    )
