"""系统工具集：Agent 管理自己的配置、规则、偏好（Agent 自管理核心）。"""
from __future__ import annotations

import json
from typing import Any

from ...llm.base import ToolSpec
from ...llm.manager import LLMConfig, LLMManager, PROVIDER_LABELS, ProviderType
from .registry import ToolRegistry


def register_system_tools(reg: ToolRegistry, llm: LLMManager, memory, rules_path=None, audit_fn=None) -> None:
    # ---------- 查看系统状态 ----------
    async def get_system_status() -> dict[str, Any]:
        cfg = llm.load()
        return {
            "llm_configured": cfg is not None and bool(cfg.api_key),
            "llm": {
                "type": cfg.type if cfg else None,
                "base_url": cfg.base_url if cfg else None,
                "model": cfg.model if cfg else None,
            } if cfg else None,
            "provider_options": PROVIDER_LABELS,
            "preferences": memory.all(),
            "rules": memory.list_rules(),
            "available_tools": [t["name"] for t in reg.list()],
        }

    reg.register(
        ToolSpec(
            "get_system_status",
            "查看系统当前状态：LLM 配置、偏好、规则、可用工具",
            {},
            doc=(
                "用途：查看 Agent Drive 系统全貌。\n"
                "参数：无。\n"
                "输出：{llm_configured, llm, provider_options, preferences, rules, available_tools}。\n"
                "注意：不暴露 API Key 明文。"
            ),
        ),
        get_system_status,
        group="system",
    )

    # ---------- 配置 LLM（Agent 自管理核心） ----------
    async def set_llm_provider(
        type: ProviderType,
        base_url: str,
        api_key: str,
        model: str,
    ) -> dict[str, Any]:
        cfg = LLMConfig(type=type, base_url=base_url, api_key=api_key, model=model)
        diag = await llm.test(cfg)  # 先测试再保存
        if not diag.get("ok"):
            return {"saved": False, "test": diag, "message": "连接测试失败，配置未保存"}
        llm.save(cfg)
        return {"saved": True, "test": diag, "message": f"已保存并验证通过: {PROVIDER_LABELS[type]}"}

    reg.register(
        ToolSpec(
            "set_llm_provider",
            "修改并测试 LLM 提供方配置",
            {
                "type": "object",
                "properties": {
                    "type": {"type": "string", "enum": ["openai_compat", "openai_responses", "anthropic"]},
                    "base_url": {"type": "string", "description": "API 地址，如 https://api.deepseek.com/v1"},
                    "api_key": {"type": "string"},
                    "model": {"type": "string", "description": "模型名，如 deepseek-chat"},
                },
                "required": ["type", "base_url", "api_key", "model"],
            },
        ),
        set_llm_provider,
        level="yellow",
        group="system",
    )

    # ---------- 测试 LLM 连接 ----------
    async def test_llm_connection() -> dict[str, Any]:
        if not llm.is_configured():
            return {"ok": False, "message": "尚未配置 LLM"}
        cfg = llm.load()
        return await llm.test(cfg)

    reg.register(
        ToolSpec(
            "test_llm_connection",
            "测试当前 LLM 配置是否可用",
            {},
            doc=(
                "用途：诊断当前 LLM 连接（延迟/模型/工具支持）。\n"
                "参数：无。\n"
                "输出：{ok, model, latency_ms, supports_tools, error?}。"
            ),
        ),
        test_llm_connection,
        group="system",
    )

    # ---------- 偏好管理 ----------
    async def set_preference(key: str, value: str) -> dict[str, Any]:
        memory.set(key, value)
        return {"saved": True, "preferences": memory.all()}

    reg.register(
        ToolSpec(
            "set_preference",
            "设置用户偏好（语言/整理风格/命名规则等），永久保存",
            {
                "type": "object",
                "properties": {
                    "key": {"type": "string", "description": "偏好名，如 language / organize_style / naming_rule"},
                    "value": {"type": "string"},
                },
                "required": ["key", "value"],
            },
            doc=(
                "用途：保存用户长期偏好。用户表达习惯（语言、整理风格、命名规则）时调用。\n"
                "参数：key（必填）偏好名；value（必填）偏好值。\n"
                "输出：{saved, preferences} 当前全部偏好。\n"
                "注意：系统提示会加载这些偏好，直接影响 Agent 行为。"
            ),
        ),
        set_preference,
        level="yellow",
        group="system",
    )

    # ---------- 规则管理（自动化） ----------
    async def add_rule(rule: str) -> dict[str, Any]:
        memory.add_rule(rule)
        return {"saved": True, "rules": memory.list_rules()}

    reg.register(
        ToolSpec(
            "add_rule",
            "添加一条自动化规则",
            {
                "type": "object",
                "properties": {"rule": {"type": "string", "description": "规则的自然语言描述"}},
                "required": ["rule"],
            },
            doc=(
                "用途：添加自动化规则（如'下载的文件自动归档到 Downloads'）。\n"
                "参数：rule（必填）规则描述。\n"
                "输出：{saved, rules} 当前全部规则。\n"
                "注意：M3 起规则会被定时执行，当前仅记录。"
            ),
        ),
        add_rule,
        level="yellow",
        group="system",
    )

    async def remove_rule(index: int) -> dict[str, Any]:
        ok = memory.remove_rule(index)
        return {"removed": ok, "rules": memory.list_rules()}

    reg.register(
        ToolSpec(
            "remove_rule",
            "删除一条规则",
            {"type": "object", "properties": {"index": {"type": "integer", "description": "规则序号（从0开始）"}}, "required": ["index"]},
            doc=(
                "用途：删除指定序号的规则。\n"
                "参数：index（必填）规则序号。\n"
                "输出：{removed, rules}。序号无效时 removed=false。"
            ),
        ),
        remove_rule,
        level="yellow",
        group="system",
    )

    # ---------- 审计 ----------
    async def view_audit_log(limit: int = 20) -> str:
        if audit_fn is None:
            return "审计功能未启用"
        return audit_fn(limit)

    reg.register(
        ToolSpec(
            "view_audit_log",
            "查看最近的操作审计日志",
            {"type": "object", "properties": {"limit": {"type": "integer", "description": "查看条数，默认20"}}},
            doc=(
                "用途：查看 Agent 最近执行过的所有操作记录（透明性）。\n"
                "参数：limit（可选）。\n"
                "输出：最近 N 条审计事件文本。"
            ),
        ),
        view_audit_log,
        group="system",
    )
