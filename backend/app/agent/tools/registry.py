"""工具注册表：Agent 的工具集（ACI）核心。

每个工具 = ToolSpec(JSON Schema + 详细文档) + 执行函数 + 可选 Critic 验证。
安全分级:
  green  = 查询，自动执行
  yellow = 低风险写，对话中执行（前端可展示）
  red    = 高风险写，需要用户确认

Critic 反馈循环：工具执行后调用 validator 验证结果，失败则返回结构化错误，
让 Agent 能理解并重试/降级（Actor-Critic 模式）。
"""
from __future__ import annotations

import inspect
import json
from typing import Any, Awaitable, Callable

from ...llm.base import ToolSpec


class Tool:
    def __init__(self, spec: ToolSpec, fn: Callable[..., Awaitable[Any]], level: str = "green",
                 validator: Callable[[dict, Any], str | None] | None = None):
        self.spec = spec
        self.fn = fn
        self.level = level  # green / yellow / red
        self.validator = validator  # Critic: (arguments, result) -> None 或错误信息

    def manual(self) -> str:
        """生成工具手册条目（系统提示用，按 API 文档标准）"""
        doc = self.spec.doc or self.spec.description
        return f"- `{self.spec.name}` (级别:{self.level})\n  {doc}"


class ToolRegistry:
    def __init__(self):
        self._tools: dict[str, Tool] = {}

    def register(self, spec: ToolSpec, fn: Callable[..., Awaitable[Any]], level: str = "green",
                 validator: Callable[[dict, Any], str | None] | None = None) -> None:
        self._tools[spec.name] = Tool(spec, fn, level, validator)

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def specs(self) -> list[ToolSpec]:
        return [t.spec for t in self._tools.values()]

    def manual(self) -> str:
        """完整工具手册（写入系统提示，实现'系统提示=API文档'）"""
        return "\n".join(t.manual() for t in self._tools.values())

    def list(self) -> list[dict[str, Any]]:
        return [
            {"name": t.spec.name, "description": t.spec.description, "level": t.level}
            for t in self._tools.values()
        ]

    async def execute(self, name: str, arguments: dict[str, Any]) -> str:
        tool = self.get(name)
        if tool is None:
            return json.dumps({"ok": False, "error": f"工具不存在: {name}"}, ensure_ascii=False)
        try:
            # 只传函数签名中接受的参数，避免多余参数报错
            sig = inspect.signature(tool.fn)
            accepted = set(sig.parameters.keys())
            kwargs = {k: v for k, v in arguments.items() if k in accepted}
            result = await tool.fn(**kwargs)

            # ---- Critic 验证循环：程序验证工具输出 ----
            if tool.validator is not None:
                err = tool.validator(arguments, result)
                if err:
                    return json.dumps({"ok": False, "error": f"验证失败: {err}"}, ensure_ascii=False)

            # 结构化结果统一序列化为 JSON，LLM 更易解析
            if isinstance(result, (dict, list, tuple)):
                return json.dumps(result, ensure_ascii=False, default=str)
            return str(result)
        except Exception as e:
            # 结构化错误：LLM 能读懂失败原因并决定重试/降级
            return json.dumps(
                {"ok": False, "error": f"{type(e).__name__}: {e}"}, ensure_ascii=False
            )
