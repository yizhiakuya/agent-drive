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
from collections.abc import Callable
from typing import Any

from ..core.retry import is_retryable_error
from ..llm.base import LLMProvider, ToolSpec
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
                    "arguments": t.get("arguments", {}),
                    "output": t.get("output", "")[:2000],
                    "parsed": t.get("parsed"),
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

        # ---- 意图路由：闲聊轻量路径 + 工具组检索 ----
        mode, tool_groups = classify(user_message)
        if mode == "chat" and not confirmed:
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

        # ---- Dreaming 巩固：每日首次对话时，把昨天的笔记蒸馏进 MEMORY.md ----
        try:
            await self._dream()
        except Exception:
            pass

        # ---- 自动压缩：历史超阈值 → LLM 摘要早期消息（滚动摘要） ----
        eff_history = history or []
        hist_tokens = count_messages_tokens([h for h in eff_history if h.get("role") in ("user", "assistant")])
        if hist_tokens > self.context_budget * self.compress_threshold:
            rolling = None
            if self.sessions is not None and sid:
                meta = self.sessions.get(sid)
                rolling = meta.get("rolling_summary") if meta else None
            compressed, new_summary = await compress_history(
                self.llm, eff_history, self.compress_keep_recent, rolling
            )
            if new_summary and self.sessions is not None and sid:
                self.sessions.update_meta(sid, rolling_summary=new_summary)
            eff_history = compressed

        # 会话级确认状态（修复 A2：pending 持久化 + 防重放）
        stored_pending = None
        consumed_nonces: set[str] = set()
        prev_trace: list[dict[str, Any]] = []
        if self.sessions is not None and sid:
            meta = self.sessions.get(sid) or {}
            stored_pending = meta.get("pending_confirmation")
            consumed_nonces = set(meta.get("consumed_nonces", []))
            prev_trace = meta.get("last_trace", [])

        tool_trace: list[dict[str, Any]] = []

        # ---- 确认恢复：签名验证通过 → 确定性重放 pending 工具（不依赖 LLM 重新推导） ----
        confirmed_replayed = False
        if stored_pending is not None and confirmed:
            approved = False
            for c in confirmed:
                ok, _err = verify_confirmation(stored_pending, c, consumed_nonces)
                if ok:
                    approved = True
                    consumed_nonces.add(stored_pending.get("nonce", ""))
                    break
            if approved:
                ptool, pargs = stored_pending.get("tool"), stored_pending.get("arguments", {})
                self.audit(f"[confirmed-exec:{ptool}] {json.dumps(pargs, ensure_ascii=False)}")
                yield ("tool_start", {"step": 1, "tool": ptool, "arguments": pargs})
                output, entry = await self._execute_tool(ptool, pargs, 1)
                tool_trace.append(entry)
                yield ("tool_trace", entry)
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
                confirmed_replayed = True
                # 跳过 LLM 重新规划，直接进入循环（LLM 会基于执行结果回复）

        if not confirmed_replayed:
            messages = self._build_messages(user_message, eff_history, tool_groups)
        self._last_messages = messages

        for step in range(self.max_steps):
            result = await self.llm.chat(messages, tools=self.tools.specs(tool_groups))
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
                    # 工具调用记录持久化（历史恢复时重建内联步骤节点）
                    for t in tool_trace:
                        try:
                            self.sessions.append(sid, {
                                "role": "tool_call",
                                "tool": t.get("tool"),
                                "arguments": t.get("arguments", {}),
                                "output": t.get("output", "")[:2000],
                                "parsed": t.get("parsed"),
                                "ts": time.time(),
                            })
                        except Exception:
                            pass
                    self.sessions.append(sid, {"role": "assistant", "content": full_reply, "ts": time.time()})
                    # 会话恢复状态：工具轨迹（防重放复用）+ 已消费 nonce
                    try:
                        self.sessions.update_meta(sid, last_trace=tool_trace, consumed_nonces=sorted(consumed_nonces))
                    except Exception:
                        pass
                    # 每日笔记（工作层）：一行活动记录，供日后检索与巩固
                    try:
                        self.memory.daily_note(
                            f"[{time.strftime('%H:%M')}] 用户: {user_message[:60]} → {full_reply[:60]}"
                        )
                    except Exception:
                        pass
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

                # ---- 安全护栏：red 级操作必须签名确认（防伪造/防重放） ----
                # 任何 red 级调用都必须过签名验证，不做列表匹配短路
                if tool is not None and tool.level == "red":
                    approved = False
                    if stored_pending is not None and stored_pending.get("tool") == tc.name:
                        # 已有待确认操作：只接受签名验证通过，失败不覆盖原 pending
                        for c in confirmed:
                            ok, _err = verify_confirmation(stored_pending, c, consumed_nonces)
                            if ok:
                                approved = True
                                consumed_nonces.add(stored_pending.get("nonce", ""))
                                break
                        if not approved:
                            # 验证失败（伪造/过期/未提供）→ 原 pending 原样返回，不重签
                            self._persist_tool_trace(sid, tool_trace)
                            self.audit(f"[pending-confirm-reject:{tc.name}] 确认验证失败")
                            yield ("done", {
                                "steps": step + 1,
                                "latency_ms": int((time.time() - started) * 1000),
                                "pending_confirmation": stored_pending,
                                "session_id": sid,
                                "tool_trace": tool_trace,
                                "plan": self.plan_state.get("steps", []),
                                "usage": self.usage,
                                "context_usage": self._context_usage(),
                            })
                            return
                        # 已确认：清除持久化的 pending，继续执行
                        if self.sessions is not None and sid:
                            self.sessions.update_meta(sid, pending_confirmation=None)
                        stored_pending = None
                    else:
                        # 新 red 请求：签发 pending 并持久化
                        pending = issue_confirmation(tc.name, tc.arguments)
                        self._persist_tool_trace(sid, tool_trace)
                        self.audit(f"[pending-confirm:{tc.name}] {json.dumps(tc.arguments, ensure_ascii=False)}")
                        if self.sessions is not None and sid:
                            self.sessions.update_meta(sid, pending_confirmation=pending)
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

                # 工具执行前发 tool_start（前端内联步骤：执行中状态）
                yield ("tool_start", {
                    "step": step + 1,
                    "tool": tc.name,
                    "arguments": tc.arguments,
                })
                # 防重放：确认后的整轮重放会再次"调用"已执行过的工具 →
                # 复用上次执行结果（避免 append/remember 等 yellow 工具双写）
                prev = None
                if tool is not None and tool.level != "red":
                    for t in prev_trace:
                        if t.get("tool") == tc.name and t.get("arguments") == tc.arguments:
                            prev = t
                            break
                if prev is not None and prev.get("output"):
                    output = prev["output"]
                    entry = {
                        "step": step + 1,
                        "tool": tc.name,
                        "arguments": tc.arguments,
                        "output": prev["output"][:500],
                        "parsed": prev.get("parsed"),
                        "replayed": True,
                    }
                else:
                    output, entry = await self._execute_tool(tc.name, tc.arguments, step + 1)
                tool_trace.append(entry)
                yield ("tool_trace", entry)
                messages.append({"role": "tool", "tool_call_id": tc.id, "content": output})
                # 轮内压缩：工具往返膨胀 → 合并早期工具结果
                if count_messages_tokens(messages) > self.context_budget * self.roundtrip_compress_threshold:
                    messages = compress_tool_roundtrips(messages, keep_roundtrips=4)
                self._last_messages = messages

        # 步数耗尽：先发说明文本再 done（修复 R3：不再静默失败）
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
        msgs = self.sessions.messages(session_id) if self.sessions is not None else []
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
