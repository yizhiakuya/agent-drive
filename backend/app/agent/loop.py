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

import asyncio
import inspect
import json
import time
from collections.abc import Callable
from typing import Any

from ..core.logging import redact_text, redact_value
from ..core.retry import is_retryable_error
from ..llm.base import LLMProvider, LLMStreamChunk, ToolSpec
from .confirm import issue_confirmation, verify_confirmation
from .context import (
    build_history,
    compress_history,
    compress_tool_roundtrips,
    count_messages_tokens,
    estimate_tokens,
    try_parse_json,
)
from .memory.preferences import MemoryStore
from .memory.sessions import SessionStore
from .prompt import build_chat_prompt, build_system_prompt
from .router import classify
from .sanitize import ToolMarkupStripper, sanitize_tool_markup
from .skills import SkillsRegistry
from .tools.plan import register_plan_tools
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
        compress_threshold: float = 0.6,
        compress_keep_recent: int = 8,
        roundtrip_compress_threshold: float = 0.9,
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
        self.compress_threshold = compress_threshold
        self.compress_keep_recent = compress_keep_recent
        self.roundtrip_compress_threshold = roundtrip_compress_threshold
        self.plan_state: dict[str, Any] = {"steps": []}
        self.usage: dict[str, int] = {"prompt_tokens": 0, "completion_tokens": 0}
        self._reasoning_parts: list[str] = []
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
            group="skills",
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

    @staticmethod
    def _stream_parts(chunk: Any) -> tuple[str, str]:
        if isinstance(chunk, str):
            return chunk, ""
        if isinstance(chunk, LLMStreamChunk):
            return chunk.text, chunk.reasoning
        if isinstance(chunk, dict):
            return str(chunk.get("text") or ""), str(chunk.get("reasoning") or "")
        return str(getattr(chunk, "text", "") or ""), str(getattr(chunk, "reasoning", "") or "")

    @staticmethod
    def _accepts_kwarg(method: Any, name: str) -> bool:
        try:
            return name in inspect.signature(method).parameters
        except (TypeError, ValueError):
            return True

    async def _chat_with_thinking(self, messages, tools=None, thinking_level="auto"):
        kwargs: dict[str, Any] = {}
        if tools is not None:
            kwargs["tools"] = tools
        if self._accepts_kwarg(self.llm.chat, "thinking_level"):
            kwargs["thinking_level"] = thinking_level
        return await self.llm.chat(messages, **kwargs)

    def _stream_with_thinking(self, messages, tools=None, thinking_level="auto"):
        kwargs: dict[str, Any] = {}
        if tools is not None:
            kwargs["tools"] = tools
        if self._accepts_kwarg(self.llm.stream_chat, "thinking_level"):
            kwargs["thinking_level"] = thinking_level
        return self.llm.stream_chat(messages, **kwargs)

    def _reasoning_event(self, text: str):
        if not text:
            return None
        self._reasoning_parts.append(text)
        return "reasoning", {"text": text}

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

    # ---------- Dreaming 巩固 ----------
    async def _dream(self) -> None:
        """把昨天的工作层笔记蒸馏进 MEMORY.md（每天最多一次）。"""
        from datetime import date, timedelta
        yesterday = (date.today() - timedelta(days=1)).isoformat()
        if self.memory.last_dream() >= yesterday:
            return  # 今天已巩固过
        notes = self.memory.yesterday_notes()
        if len(notes) < 3:
            return  # 笔记太少不值得巩固
        transcript = "\n".join(notes[-30:])
        result = await self.llm.chat([{
            "role": "user",
            "content": (
                "以下是昨天的会话活动笔记。请提炼值得长期记住的持久事实/决策"
                "（用户背景、偏好、项目进展、重要约定），每条一行，不超过 5 条。"
                "没有值得记的内容输出'无'。\n\n" + transcript
            ),
        }])
        for line in (result.content or "").splitlines():
            line = line.strip().lstrip("-• ").strip()
            if line and line != "无" and len(line) > 3:
                self.memory.remember(f"{line}（巩固自{yesterday}）")
        # 标记已巩固
        self.memory.mark_dreamed(yesterday)

    # ---------- 自动标题 ----------
    async def _generate_title(self, sid: str) -> str | None:
        """用 LLM 为会话生成简短标题（首次问答后调用一次）。"""
        sessions = self.sessions
        if sessions is None:
            return None
        msgs = sessions.messages(sid)[:4]
        transcript = "\n".join(
            f"{'用户' if m['role'] == 'user' else 'Agent'}: {str(m.get('content', ''))[:80]}"
            for m in msgs
        )
        result = await self.llm.chat([{
            "role": "user",
            "content": "给以下对话起一个简短标题（不超过 10 个字，不要标点符号，直接输出标题本身）：\n" + transcript,
        }])
        title = sanitize_tool_markup((result.content or "").strip())
        title = title.strip('。！!？?""“”\' ')[:20]
        if title:
            sessions.update_summary(sid, summary=None, title=title)
        return title or None

    # ---------- 消息组装 ----------
    def _build_messages(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None,
        tool_groups: list[str] | None = None,
    ) -> list[dict[str, Any]]:
        system_prompt = build_system_prompt(self.memory, self.tools, {}, self.sessions, self.skills, tool_groups)
        return [
            {"role": "system", "content": system_prompt},
            *build_history(history, self.context_budget),
            {"role": "user", "content": user_message},
        ]

    # ---------- 工具轨迹持久化 ----------
    def _persist_tool_trace(self, sid: str | None, tool_trace: list[dict[str, Any]]) -> None:
        """把已执行的工具记录写入会话消息流（历史恢复用）"""
        if self.sessions is None or not sid:
            return
        for t in tool_trace:
            try:
                self.sessions.append(sid, {
                    "role": "tool_call",
                    "tool": t.get("tool"),
                    # 密钥脱敏：工具参数（如 api_key）与输出落库前必须过 redact，
                    # 勿存明文（审计层有脱敏，会话/记忆层此前缺失）
                    "arguments": redact_value(t.get("arguments", {})),
                    "output": redact_text(t.get("output", "")[:2000]),
                    "parsed": redact_value(t.get("parsed")),
                    "ts": time.time(),
                })
            except Exception:
                pass

    # ---------- 工具执行（审计 + 执行 + 重试 + 截断 + 结构化） ----------
    async def _execute_tool(self, name: str, arguments: dict[str, Any], step: int) -> tuple[str, dict[str, Any]]:
        self.audit(f"[tool:{name}] {json.dumps(arguments, ensure_ascii=False)}")
        output = await self.tools.execute(name, arguments)
        if is_retryable_error(output):
            self.audit(f"[retry:{name}] 瞬态错误，重试")
            output = await self.tools.execute(name, arguments)
        if len(output) > self.max_tool_output:
            output = output[:self.max_tool_output] + "\n...[截断]"
        # parsed 用完整输出解析（此前用 output[:500] 截断片段，get_system_status
        # 等 >500B 的 JSON 必然截断失败 → parsed=None → 前端降级为半截原文）
        entry = {
            "step": step,
            "tool": name,
            "arguments": arguments,
            "output": output[:500],
            "parsed": try_parse_json(output),
            "output_truncated": len(output) > 500,
        }
        return output, entry

    # ---------- 核心生成器 ----------
    async def _execute(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None,
        confirmations: list[dict[str, Any]] | None,
        session_id: str | None,
        tool_groups: tuple[str, ...] | list[str] | None = None,
        thinking_level: str = "auto",
    ):
        """统一执行核心（编排壳）。yields: ("text", c) | ("tool_start", e) | ("tool_trace", e) | ("done", m)

        职责：意图路由 → 分发到闲聊/任务路径。具体逻辑在 _chat_path/_task_path。
        tool_groups: 外部指定工具组（自动化执行受限组）；None 时走 router 分类。
        """
        self._reasoning_parts = []
        confirmed = confirmations or []
        mode, tool_groups = classify(user_message) if tool_groups is None else ("task", tool_groups)
        if mode == "chat" and not confirmed:
            # 会话存在 pending 确认时，即使消息是闲聊类（如口头"确认"），
            # 也走任务路径 → 口头确认拦截（重新签发签名确认，绝不口头执行）
            has_pending = False
            if self.sessions is not None and session_id:
                meta = self.sessions.get(session_id)
                has_pending = bool(meta and meta.get("pending_confirmation"))
            # 任务会话续接：上一轮走过任务路径的会话里，短消息（"继续/确认/随便"）
            # 不能被 classify 降级为 chat——chat 路径无工具，模型会"假装"调工具
            # （生产实测：零工具执行、配置未写入、前端无工具卡片）。
            # 原则：chat 误判 = 能力归零；task 误判只是多花 token。
            if not has_pending and tool_groups is None and self._last_routed_task(session_id):
                mode, tool_groups = "task", None
            elif not has_pending:
                async for ev in self._chat_path(user_message, history, session_id, thinking_level):
                    yield ev
                return
        async for ev in self._task_path(
            user_message, history, confirmed, session_id, tool_groups, thinking_level
        ):
            yield ev

    # ---------- 闲聊轻量路径 ----------
    async def _chat_path(self, user_message: str, history, session_id: str | None, thinking_level: str):
        """闲聊：精简提示 + 无工具 + 少量历史。也持久化为会话（用户视角：聊了就有记录）。"""
        started = time.time()
        sid = session_id
        if self.sessions is not None and (sid is None or self.sessions.get(sid) is None):
            meta = self.sessions.create()
            sid = meta["id"]
        reply_parts: list[str] = []
        chat_messages = [
            {"role": "system", "content": build_chat_prompt(self.memory)},
            *build_history(history, 2000),
            {"role": "user", "content": user_message},
        ]
        stripper = ToolMarkupStripper()  # 剔除模型正文里模拟工具调用的 DSML/XML 标记
        try:
            async for chunk in self._stream_with_thinking(chat_messages, thinking_level=thinking_level):
                text, reasoning = self._stream_parts(chunk)
                if reasoning:
                    event = self._reasoning_event(reasoning)
                    if event:
                        yield event
                self._add_usage(None, text + reasoning)
                clean = stripper.feed(text)
                if clean:
                    reply_parts.append(clean)
                    yield ("text", clean)
            tail = stripper.flush()
            if tail:
                reply_parts.append(tail)
                yield ("text", tail)
        except (NotImplementedError, TypeError):
            result = await self._chat_with_thinking(chat_messages, thinking_level=thinking_level)
            self._add_usage(result.usage, result.reasoning)
            event = self._reasoning_event(result.reasoning)
            if event:
                yield event
            clean = sanitize_tool_markup(result.content or "")
            reply_parts.append(clean)
            yield ("text", clean)
        # 持久化（标题取首句，会话恢复时可回看；密钥脱敏后落库）
        if self.sessions is not None and sid:
            self.sessions.append(sid, {"role": "user", "content": redact_text(user_message), "ts": time.time()})
            assistant_message = {
                "role": "assistant",
                "content": redact_text("".join(reply_parts)),
                "ts": time.time(),
            }
            if self._reasoning_parts:
                assistant_message["reasoning"] = redact_text("".join(self._reasoning_parts))
            self.sessions.append(sid, assistant_message)
            self.sessions.update_meta(sid, last_routed="chat")
            meta_now = self.sessions.get(sid)
            if meta_now and (not meta_now.get("title") or meta_now.get("title") == "新会话"):
                title = redact_text(user_message.strip().replace("\n", " ")[:24]) or "闲聊"
                self.sessions.update_meta(sid, title=title)
        yield ("done", {
            "steps": 1,
            "latency_ms": int((time.time() - started) * 1000),
            "session_id": sid,
            "routed": "chat",
            "tool_trace": [],
            "plan": [],
            "usage": self.usage,
            "context_usage": self._chat_context_usage(chat_messages),
        })

    def _chat_context_usage(self, chat_messages: list[dict[str, Any]]) -> dict[str, Any]:
        used = sum(estimate_tokens(str(m.get("content", ""))) for m in chat_messages)
        return {
            "used": used,
            "total": self.context_window,
            "percent": round(used / self.context_window * 100, 1) if self.context_window else 0,
        }

    def _last_routed_task(self, sid: str | None) -> bool:
        """会话上一轮是否走过任务路径（短消息续接路由用）。

        新格式看 meta.last_routed；旧会话（升级前）无该字段时回退 last_trace：
        有过工具轨迹即视为任务会话（见 tests/unit/test_session_routing.py）。
        """
        if self.sessions is None or not sid:
            return False
        meta = self.sessions.get(sid)
        if not meta:
            return False
        if meta.get("last_routed") == "task":
            return True
        return bool(meta.get("last_trace"))

    # ---------- 任务路径 ----------
    async def _task_path(
        self, user_message: str, history, confirmed, session_id: str | None, tool_groups, thinking_level: str
    ):
        """任务：会话初始化 + dreaming + 压缩 + 确认重放 + 进入工具循环。"""
        started = time.time()

        # 会话初始化
        sid = session_id
        if self.sessions is not None:
            if sid is None or self.sessions.get(sid) is None:
                meta = self.sessions.create()
                sid = meta["id"]
            # 密钥脱敏后落库：用户可能把 key 贴进聊天，会话历史不留明文
            self.sessions.append(sid, {"role": "user", "content": redact_text(user_message), "ts": time.time()})
            # 标记本轮路径：短消息续接路由依赖（chat/task 均写，见 _execute）
            self.sessions.update_meta(sid, last_routed="task")

        # Dreaming 巩固（尽力而为）
        # 超时保护：dreaming 最多 15s（失败/超时不阻塞首包）
        try:
            await asyncio.wait_for(self._dream(), timeout=15.0)
        except (asyncio.TimeoutError, Exception):
            pass  # dreaming 失败不影响任务

        # 自动压缩（滚动摘要）
        eff_history = await self._maybe_compress(history, sid)

        # 会话确认状态（防伪造/防重放）
        stored_pending, consumed_nonces, prev_trace = self._load_confirm_state(sid)

        # 口头确认（无签名）→ 重新签发签名确认，引导用户点按钮（绝不口头执行）
        if stored_pending is not None and not confirmed:
            verbal = ("确认" in user_message or "好的" in user_message or "可以" in user_message
                      or "同意" in user_message or "做吧" in user_message or "执行" in user_message
                      or user_message.strip().lower() in ("ok", "yes", "go"))
            if verbal:
                new_pending = issue_confirmation(stored_pending["tool"], stored_pending["arguments"])
                if self.sessions is not None and sid:
                    self.sessions.update_meta(sid, pending_confirmation=new_pending)
                self.audit(f"[pending-verbally-confirmed:{stored_pending['tool']}] 口头确认无签名，重新签发")
                yield ("done", {
                    "session_id": sid,
                    "tool_trace": [],
                    "pending_confirmation": new_pending,
                    "steps": 0,
                    "latency_ms": int((time.time() - started) * 1000),
                })
                return

        tool_trace: list[dict[str, Any]] = []

        # 确认恢复：签名验证 → 确定性重放（不依赖 LLM 重新推导）
        messages, replay_events = await self._replay_confirmation(
            user_message, eff_history, tool_groups, stored_pending, confirmed, consumed_nonces, sid, tool_trace
        )
        for ev in replay_events:
            yield ev

        async for ev in self._task_loop(
            messages, sid, confirmed, started, tool_trace,
            stored_pending, consumed_nonces, prev_trace, user_message, tool_groups, thinking_level,
        ):
            yield ev

    async def _maybe_compress(self, history, sid: str | None):
        """历史超阈值 → LLM 摘要早期消息（滚动摘要）。"""
        eff_history = history or []
        hist_tokens = count_messages_tokens([h for h in eff_history if h.get("role") in ("user", "assistant")])
        if hist_tokens <= self.context_budget * self.compress_threshold:
            return eff_history
        rolling = None
        if self.sessions is not None and sid:
            meta = self.sessions.get(sid)
            rolling = meta.get("rolling_summary") if meta else None
        compressed, new_summary = await compress_history(self.llm, eff_history, self.compress_keep_recent, rolling)
        if new_summary and self.sessions is not None and sid:
            self.sessions.update_meta(sid, rolling_summary=new_summary)
        return compressed

    def _load_confirm_state(self, sid: str | None):
        """读会话级确认状态（pending/已消费 nonce/上次轨迹）。"""
        if self.sessions is None or not sid:
            return None, set(), []
        meta = self.sessions.get(sid) or {}
        return (
            meta.get("pending_confirmation"),
            set(meta.get("consumed_nonces", [])),
            meta.get("last_trace", []),
        )

    async def _replay_confirmation(self, user_message, eff_history, tool_groups, stored_pending, confirmed, consumed_nonces, sid, tool_trace):
        """确认恢复：合法签名 → 确定性重放 pending 工具。

        返回 (messages, events)：events 是重放产生的 tool_start/tool_trace 事件（由调用方转发）。
        """
        events: list[tuple[str, dict[str, Any]]] = []
        messages = self._build_messages(user_message, eff_history, tool_groups)
        if stored_pending is None or not confirmed:
            self._last_messages = messages
            return messages, events
        approved = False
        for c in confirmed:
            ok, _err = verify_confirmation(stored_pending, c, consumed_nonces)
            if ok:
                approved = True
                consumed_nonces.add(stored_pending.get("nonce", ""))
                break
        if not approved:
            self._last_messages = messages
            return messages, events
        ptool = stored_pending.get("tool")
        pargs = stored_pending.get("arguments", {})
        self.audit(f"[confirmed-exec:{ptool}] {json.dumps(pargs, ensure_ascii=False)}")
        events.append(("tool_start", {"step": 1, "tool": ptool, "arguments": pargs}))
        output, entry = await self._execute_tool(ptool, pargs, 1)
        tool_trace.append(entry)
        events.append(("tool_trace", entry))
        messages = self._build_messages(user_message, eff_history, tool_groups)
        messages.append({
            "role": "assistant",
            "content": "",
            "tool_calls": [{"id": "pending_confirmed", "name": ptool, "arguments": pargs}],
        })
        messages.append({"role": "tool", "tool_call_id": "pending_confirmed", "content": output})
        messages.append({
            "role": "user",
            "content": (
                f"用户已确认执行 {ptool} 操作，执行结果如上。"
                "请直接向用户汇报执行结果（操作已生效，不要重复执行或验证）。"
            ),
        })
        self._last_messages = messages
        if self.sessions is not None and sid:
            self.sessions.update_meta(sid, pending_confirmation=None)
        return messages, events

    # ---------- 工具循环 ----------
    async def _task_loop(self, messages, sid, confirmed, started, tool_trace,
                         stored_pending, consumed_nonces, prev_trace, user_message, tool_groups, thinking_level):
        """工具循环：LLM 决策 → 工具执行 → 观察，直到最终回复或步数耗尽。"""
        for step in range(self.max_steps):
            result = await self._chat_with_thinking(
                messages, tools=self.tools.specs(tool_groups), thinking_level=thinking_level
            )
            self._add_usage(result.usage, result.reasoning)

            if not result.tool_calls:
                async for ev in self._final_reply(
                    messages, result, sid, started, tool_trace, consumed_nonces, user_message, step, thinking_level
                ):
                    yield ev
                return

            event = self._reasoning_event(result.reasoning)
            if event:
                yield event

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

                # red 级：签名确认（失败/签发 pending 时返回 done 负载）
                red_done = await self._check_red_tool(tc, tool, stored_pending, confirmed, consumed_nonces, sid, step, started, tool_trace)
                if red_done is not None:
                    yield ("done", red_done)
                    return

                yield ("tool_start", {"step": step + 1, "tool": tc.name, "arguments": tc.arguments})
                output, entry = await self._execute_or_replay_tool(tc, tool, prev_trace, step)
                tool_trace.append(entry)
                yield ("tool_trace", entry)
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": output})
                if count_messages_tokens(messages) > self.context_budget * self.roundtrip_compress_threshold:
                    messages = compress_tool_roundtrips(messages, keep_roundtrips=4)
                self._last_messages = messages

        # 步数耗尽：说明文本 + done
        truncate_msg = (
            f"\n\n⚠️ 已达到本轮最大执行步数（{self.max_steps} 步），任务可能未完成。"
            "你可以回复「继续」让我接着做，或把任务拆小后再试。"
        )
        yield ("text", truncate_msg)
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

    async def _check_red_tool(self, tc, tool, stored_pending, confirmed, consumed_nonces, sid, step, started, tool_trace):
        """red 级工具确认检查。返回 None=放行执行；返回 done 负载=需要暂停。"""
        if tool is None or tool.level != "red":
            return None
        done_base = {
            "steps": step + 1,
            "latency_ms": int((time.time() - started) * 1000),
            "session_id": sid,
            "tool_trace": tool_trace,
            "plan": self.plan_state.get("steps", []),
            "usage": self.usage,
            "context_usage": self._context_usage(),
        }
        if stored_pending is not None and stored_pending.get("tool") == tc.name:
            approved = False
            for c in confirmed:
                ok, _err = verify_confirmation(stored_pending, c, consumed_nonces)
                if ok:
                    approved = True
                    consumed_nonces.add(stored_pending.get("nonce", ""))
                    break
            if not approved:
                # 验证失败（伪造/过期/未提供）→ 原 pending 原样返回
                self._persist_tool_trace(sid, tool_trace)
                self.audit(f"[pending-confirm-reject:{tc.name}] 确认验证失败")
                return {**done_base, "pending_confirmation": stored_pending}
            # 已确认：清除 pending，继续执行
            if self.sessions is not None and sid:
                self.sessions.update_meta(sid, pending_confirmation=None)
            return None
        # 新 red 请求：签发 pending
        pending = issue_confirmation(tc.name, tc.arguments)
        self._persist_tool_trace(sid, tool_trace)
        self.audit(f"[pending-confirm:{tc.name}] {json.dumps(tc.arguments, ensure_ascii=False)}")
        if self.sessions is not None and sid:
            self.sessions.update_meta(sid, pending_confirmation=pending)
        return {**done_base, "pending_confirmation": pending}

    async def _execute_or_replay_tool(self, tc, tool, prev_trace, step):
        """执行工具；确认后重放场景复用上次结果（防 yellow 双写）。"""
        prev = None
        if tool is not None and tool.level != "red":
            for t in prev_trace:
                if t.get("tool") == tc.name and t.get("arguments") == tc.arguments:
                    prev = t
                    break
        if prev is not None and prev.get("output"):
            return prev["output"], {
                "step": step + 1,
                "tool": tc.name,
                "arguments": tc.arguments,
                "output": prev["output"][:500],
                "parsed": prev.get("parsed"),
                "replayed": True,
            }
        return await self._execute_tool(tc.name, tc.arguments, step + 1)

    # ---------- 最终回复 ----------
    async def _final_reply(
        self, messages, result, sid, started, tool_trace, consumed_nonces, user_message, step, thinking_level
    ):
        """流式最终回复 + 持久化（工具轨迹/会话/笔记/标题）+ done。"""
        full_reply = ""
        stripper = ToolMarkupStripper()  # 剔除模型正文里模拟工具调用的 DSML/XML 标记
        try:
            async for chunk in self._stream_with_thinking(messages, thinking_level=thinking_level):
                text, reasoning = self._stream_parts(chunk)
                if reasoning:
                    event = self._reasoning_event(reasoning)
                    if event:
                        yield event
                self._add_usage(None, text + reasoning)
                clean = stripper.feed(text)
                if clean:
                    full_reply += clean
                    yield ("text", clean)
            tail = stripper.flush()
            if tail:
                full_reply += tail
                yield ("text", tail)
        except (NotImplementedError, TypeError):
            event = self._reasoning_event(result.reasoning)
            if event:
                yield event
            full_reply = sanitize_tool_markup(result.content or "")
            self._add_usage(None, full_reply)
            yield ("text", full_reply)

        needs_summary = False
        if self.sessions is not None and sid:
            self._persist_tool_trace(sid, tool_trace)
            assistant_message = {
                "role": "assistant",
                "content": redact_text(full_reply),
                "ts": time.time(),
            }
            if self._reasoning_parts:
                assistant_message["reasoning"] = redact_text("".join(self._reasoning_parts))
            self.sessions.append(sid, assistant_message)
            try:
                # last_trace 落库同样脱敏（含 api_key 的工具轨迹不留明文）。
                # 仅 yellow 工具依赖 last_trace 做确定性重放，而 yellow 工具不带密钥
                # 参数；red 工具走 pending 确认重放，不受影响。
                self.sessions.update_meta(
                    sid, last_trace=redact_value(tool_trace), consumed_nonces=sorted(consumed_nonces)
                )
            except Exception:
                pass
            try:
                self.memory.daily_note(
                    f"[{time.strftime('%H:%M')}] 用户: {user_message[:60]} → {full_reply[:60]}"
                )
            except Exception:
                pass
            meta = self.sessions.get(sid)
            needs_summary = (meta.get("message_count", 0) >= self.summarize_threshold
                             and not meta.get("summary")) if meta else False
            if meta and meta.get("title") == "新会话" and meta.get("message_count", 0) >= 2:
                try:
                    await self._generate_title(sid)
                except Exception:
                    pass

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

    # ---------- 非流式 API ----------
    async def run(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None = None,
        confirmations: list[dict[str, Any]] | None = None,
        session_id: str | None = None,
        tool_groups: tuple[str, ...] | list[str] | None = None,
        thinking_level: str = "auto",
    ) -> dict[str, Any]:
        """聚合事件为单次响应。tool_groups 为外部指定工具组（自动化用）。"""
        reply_parts: list[str] = []
        trace: list[dict[str, Any]] = []
        done: dict[str, Any] = {}
        async for event, payload in self._execute(
            user_message, history, confirmations, session_id, tool_groups, thinking_level
        ):
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
        tool_groups: tuple[str, ...] | list[str] | None = None,
        thinking_level: str = "auto",
    ):
        """透传事件（SSE 用）。tool_groups 为外部指定工具组（自动化用）。"""
        async for event, payload in self._execute(
            user_message, history, confirmations, session_id, tool_groups, thinking_level
        ):
            yield event, payload

    # ---------- 会话摘要 ----------
    async def summarize_session(self, session_id: str) -> dict[str, Any]:
        """用 LLM 生成会话摘要（跨会话记忆核心）。"""
        sessions = self.sessions
        if sessions is None:
            return {"ok": False, "error": "未启用会话存储"}
        meta = sessions.get(session_id)
        if meta is None:
            return {"ok": False, "error": "会话不存在"}
        msgs = sessions.messages(session_id)
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
            # 摘要同样必须过工具标记清洗：模型可能在摘要里"模拟"工具调用，
            # 原文会泄漏到会话列表（生产实测：摘要中出现 <tool_calls> 原文）
            summary = sanitize_tool_markup((result.content or "（摘要生成失败）").strip())
            title = summary[:20]
            sessions.update_summary(session_id, summary, title=title)
            return {"ok": True, "summary": summary, "title": title}
        except Exception as e:
            return {"ok": False, "error": str(e)}
