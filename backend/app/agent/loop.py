"""AgentLoop 编排引擎（agent 模块核心）。

职责单一：编排一次对话的执行流程（路由 → 工具循环 → 流式回复）。
提示词工程 → prompt.py；上下文管理 → context.py；确认判定 → confirm.py。

设计要点：
- _execute() 是唯一核心生成器：yield (event, payload)
- run()      = 聚合事件（非流式 API）
- run_stream() = 透传事件（SSE 流式 API）
- 消除 run/run_stream 重复代码（v3 重构）
"""
from __future__ import annotations

import json
import time
from typing import Any, Callable

from ..core.retry import is_retryable_error
from ..llm.base import LLMProvider, ToolSpec
from .confirm import needs_confirmation
from .context import build_history, estimate_tokens, try_parse_json
from .tools.plan import register_plan_tools
from .memory.preferences import MemoryStore
from .memory.sessions import SessionStore
from .prompt import build_chat_prompt, build_system_prompt
from .router import classify
from .skills import SkillsRegistry
from .tools.registry import ToolRegistry


class AgentLoop:
    def __init__(
        self,
        llm: LLMProvider,
        tools: ToolRegistry,
        memory: MemoryStore,
        audit: Callable[[str], None] | None = None,
        sessions: SessionStore | None = None,
        skills: SkillsRegistry | None = None,
        max_steps: int = 10,
        context_budget: int = 24000,
        max_tool_output: int = 2000,
        summarize_threshold: int = 12,
        context_window: int = 262144,
    ):
        self.llm = llm
        self.tools = tools
        self.memory = memory
        self.audit = audit or (lambda msg: None)
        self.sessions = sessions
        self.skills = skills
        self.max_steps = max_steps
        self.context_budget = context_budget
        self.max_tool_output = max_tool_output
        self.summarize_threshold = summarize_threshold
        self.context_window = context_window
        self.plan_state: dict[str, Any] = {"steps": []}
        self.usage: dict[str, int] = {"prompt_tokens": 0, "completion_tokens": 0}
        register_plan_tools(self.tools, self.plan_state)
        if skills is not None:
            self._register_skill_tool()

    # ---------- 技能工具 ----------
    def _register_skill_tool(self) -> None:
        async def read_skill(name: str) -> str:
            if self.skills is None:
                return json.dumps({"ok": False, "error": "技能系统未启用"}, ensure_ascii=False)
            skill = self.skills.get(name)
            if skill is None:
                available = [s.name for s in self.skills.list()]
                return json.dumps({"ok": False, "error": f"技能不存在: {name}", "available": available}, ensure_ascii=False)
            return skill.full_text()

        self.tools.register(
            ToolSpec(
                "read_skill",
                "加载指定技能的完整指令（使用技能前必须调用）",
                {"type": "object", "properties": {"name": {"type": "string", "description": "技能名"}}, "required": ["name"]},
                doc=(
                    "用途：加载技能包的完整操作指令。\n"
                    "参数：name（必填）技能名，见系统提示中的技能索引。\n"
                    "输出：技能的完整 SKILL.md 内容（步骤/格式/注意事项）。\n"
                    "错误：技能不存在返回 {ok:false, error, available}。"
                ),
            ),
            read_skill,
        )

    # ---------- 上下文用量 ----------
    def _add_usage(self, usage: dict[str, Any] | None, stream_text: str | None = None) -> None:
        """累计 token 用量（chat 用 usage，流式用文本估算）"""
        if usage:
            self.usage["prompt_tokens"] += int(usage.get("prompt_tokens") or 0)
            self.usage["completion_tokens"] += int(usage.get("completion_tokens") or 0)
        if stream_text:
            self.usage["completion_tokens"] += estimate_tokens(stream_text)
        self.usage["total_tokens"] = self.usage["prompt_tokens"] + self.usage["completion_tokens"]

    def _context_usage(self, messages: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        """当前上下文占用（发给 LLM 的 messages 总量），供前端进度条显示。"""
        if messages is None:
            messages = self._last_messages
        used = sum(estimate_tokens(str(m.get("content", ""))) for m in messages)
        # 加上 tool_calls 参数估算
        for m in messages:
            for tc in m.get("tool_calls", []):
                used += estimate_tokens(str(tc.get("arguments", "")))
        return {
            "used": used,
            "total": self.context_window,
            "percent": round(used / self.context_window * 100, 1) if self.context_window else 0,
        }

    # ---------- 自动标题 ----------
    async def _generate_title(self, sid: str) -> str | None:
        """用 LLM 为会话生成简短标题（首次问答后调用一次）。"""
        msgs = self.sessions.messages(sid)[:4]
        transcript = "\n".join(
            f"{'用户' if m['role'] == 'user' else 'Agent'}: {str(m.get('content', ''))[:80]}"
            for m in msgs
        )
        result = await self.llm.chat([{
            "role": "user",
            "content": "给以下对话起一个简短标题（不超过 10 个字，不要标点符号，直接输出标题本身）：\n" + transcript,
        }])
        title = (result.content or "").strip()
        title = title.strip('。！!？?""“”\' ')[:20]
        if title:
            self.sessions.update_summary(sid, summary=None, title=title)
        return title or None

    # ---------- 消息组装 ----------
    def _build_messages(self, user_message: str, history: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
        system_prompt = build_system_prompt(self.memory, self.tools, {}, self.sessions, self.skills)
        return [
            {"role": "system", "content": system_prompt},
            *build_history(history, self.context_budget),
            {"role": "user", "content": user_message},
        ]

    # ---------- 工具执行（审计 + 执行 + 重试 + 截断 + 结构化） ----------
    async def _execute_tool(self, name: str, arguments: dict[str, Any], step: int) -> tuple[str, dict[str, Any]]:
        self.audit(f"[tool:{name}] {json.dumps(arguments, ensure_ascii=False)}")
        output = await self.tools.execute(name, arguments)
        if is_retryable_error(output):
            self.audit(f"[retry:{name}] 瞬态错误，重试")
            output = await self.tools.execute(name, arguments)
        if len(output) > self.max_tool_output:
            output = output[:self.max_tool_output] + "\n...[截断]"
        entry = {
            "step": step,
            "tool": name,
            "arguments": arguments,
            "output": output[:500],
            "parsed": try_parse_json(output[:500]),
        }
        return output, entry

    # ---------- 核心生成器 ----------
    async def _execute(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None,
        confirmations: list[dict[str, Any]] | None,
        session_id: str | None,
    ):
        """统一执行核心。yields: ("text", chunk) | ("tool_trace", entry) | ("done", meta)"""
        confirmed = confirmations or []
        started = time.time()

        # ---- 意图路由：闲聊轻量路径 ----
        if classify(user_message) == "chat" and not confirmed:
            chat_messages = [
                {"role": "system", "content": build_chat_prompt(self.memory)},
                *build_history(history, 2000),  # 闲聊只需少量历史
                {"role": "user", "content": user_message},
            ]
            try:
                async for chunk in self.llm.stream_chat(chat_messages):
                    self._add_usage(None, chunk)
                    yield ("text", chunk)
            except (NotImplementedError, TypeError):
                result = await self.llm.chat(chat_messages)
                self._add_usage(result.usage)
                yield ("text", result.content or "")
            yield ("done", {
                "steps": 1,
                "latency_ms": int((time.time() - started) * 1000),
                "session_id": session_id,
                "routed": "chat",
                "tool_trace": [],
                "plan": [],
                "usage": self.usage,
                "context_usage": {
                    "used": sum(estimate_tokens(str(m.get("content", ""))) for m in chat_messages),
                    "total": self.context_window,
                    "percent": round(sum(estimate_tokens(str(m.get("content", ""))) for m in chat_messages) / self.context_window * 100, 1) if self.context_window else 0,
                },
            })
            return

        # ---- 任务路径：完整 Agentic Loop ----
        sid = session_id
        if self.sessions is not None:
            if sid is None or self.sessions.get(sid) is None:
                meta = self.sessions.create()
                sid = meta["id"]
            self.sessions.append(sid, {"role": "user", "content": user_message, "ts": time.time()})

        messages = self._build_messages(user_message, history)
        self._last_messages = messages
        tool_trace: list[dict[str, Any]] = []

        for step in range(self.max_steps):
            result = await self.llm.chat(messages, tools=self.tools.specs())
            self._add_usage(result.usage)

            if not result.tool_calls:
                # ---- 流式输出最终回复 ----
                full_reply = ""
                try:
                    async for chunk in self.llm.stream_chat(messages):
                        self._add_usage(None, chunk)
                        full_reply += chunk
                        yield ("text", chunk)
                except (NotImplementedError, TypeError):
                    full_reply = result.content or ""
                    self._add_usage(None, full_reply)
                    yield ("text", full_reply)
                if self.sessions is not None and sid:
                    self.sessions.append(sid, {"role": "assistant", "content": full_reply, "ts": time.time()})
                    meta = self.sessions.get(sid)
                    needs_summary = (meta.get("message_count", 0) >= self.summarize_threshold
                                     and not meta.get("summary"))
                    # 自动标题：会话首次问答后生成（尽力而为，失败不影响主流程）
                    if meta and meta.get("title") == "新会话" and meta.get("message_count", 0) >= 2:
                        try:
                            await self._generate_title(sid)
                        except Exception:
                            pass
                else:
                    needs_summary = False
                yield ("done", {
                    "steps": step + 1,
                    "latency_ms": int((time.time() - started) * 1000),
                    "session_id": sid,
                    "needs_summary": needs_summary,
                    "routed": "task",
                    "tool_trace": tool_trace,
                    "plan": self.plan_state.get("steps", []),
                    "usage": self.usage,
                    "context_usage": self._context_usage(),
                })
                return

            messages.append({
                "role": "assistant",
                "content": result.content or "",
                "tool_calls": [
                    {"id": tc.id, "name": tc.name, "arguments": tc.arguments}
                    for tc in result.tool_calls
                ],
            })
            for tc in result.tool_calls:
                tool = self.tools.get(tc.name)

                # ---- 安全护栏：red 级操作必须确认 ----
                if needs_confirmation(tool, tc, confirmed):
                    pending = {
                        "tool": tc.name,
                        "arguments": tc.arguments,
                        "message": f"Agent 请求执行高风险操作：{tc.name}({json.dumps(tc.arguments, ensure_ascii=False)})",
                    }
                    self.audit(f"[pending-confirm:{tc.name}] {json.dumps(tc.arguments, ensure_ascii=False)}")
                    yield ("done", {
                        "steps": step + 1,
                        "latency_ms": int((time.time() - started) * 1000),
                        "pending_confirmation": pending,
                        "session_id": sid,
                        "tool_trace": tool_trace,
                        "plan": self.plan_state.get("steps", []),
                        "usage": self.usage,
                        "context_usage": self._context_usage(),
                    })
                    return

                output, entry = await self._execute_tool(tc.name, tc.arguments, step + 1)
                tool_trace.append(entry)
                yield ("tool_trace", entry)
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": output})
                self._last_messages = messages

        yield ("done", {
            "steps": self.max_steps,
            "latency_ms": int((time.time() - started) * 1000),
            "truncated": True,
            "session_id": sid,
            "tool_trace": tool_trace,
            "plan": self.plan_state.get("steps", []),
            "usage": self.usage,
            "context_usage": self._context_usage(),
        })

    # ---------- 非流式 API ----------
    async def run(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None = None,
        confirmations: list[dict[str, Any]] | None = None,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        """聚合事件为单次响应。"""
        reply_parts: list[str] = []
        trace: list[dict[str, Any]] = []
        done: dict[str, Any] = {}
        async for event, payload in self._execute(user_message, history, confirmations, session_id):
            if event == "text":
                reply_parts.append(payload)
            elif event == "tool_trace":
                trace.append(payload)
            elif event == "done":
                done = payload
        return {
            "reply": "".join(reply_parts),
            "tool_trace": done.get("tool_trace", trace),
            "steps": done.get("steps", 0),
            "latency_ms": done.get("latency_ms", 0),
            "pending_confirmation": done.get("pending_confirmation"),
            "session_id": done.get("session_id"),
            "needs_summary": done.get("needs_summary", False),
            "truncated": done.get("truncated", False),
            "routed": done.get("routed"),
            "plan": done.get("plan", []),
            "usage": done.get("usage", self.usage),
            "context_usage": done.get("context_usage", {}),
        }

    # ---------- 流式 API ----------
    async def run_stream(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None = None,
        confirmations: list[dict[str, Any]] | None = None,
        session_id: str | None = None,
    ):
        """透传事件（SSE 用）。"""
        async for event, payload in self._execute(user_message, history, confirmations, session_id):
            yield event, payload

    # ---------- 会话摘要 ----------
    async def summarize_session(self, session_id: str) -> dict[str, Any]:
        """用 LLM 生成会话摘要（跨会话记忆核心）。"""
        if self.sessions is None:
            return {"ok": False, "error": "未启用会话存储"}
        meta = self.sessions.get(session_id)
        if meta is None:
            return {"ok": False, "error": "会话不存在"}
        msgs = self.sessions.messages(session_id)
        transcript = "\n".join(
            f"{'用户' if m['role'] == 'user' else 'Agent'}: {str(m.get('content', ''))[:300]}"
            for m in msgs[-30:]
        )
        prompt = (
            "请为下面的对话生成一份简洁的会话摘要（中文，80字以内），"
            "突出：1)用户关心的话题 2)已完成/进行中的事项 3)值得记住的用户偏好或事实。\n\n"
            f"对话记录:\n{transcript}"
        )
        try:
            result = await self.llm.chat([{"role": "user", "content": prompt}])
            summary = (result.content or "（摘要生成失败）").strip()
            title = summary[:20]
            self.sessions.update_summary(session_id, summary, title=title)
            return {"ok": True, "summary": summary, "title": title}
        except Exception as e:
            return {"ok": False, "error": str(e)}
