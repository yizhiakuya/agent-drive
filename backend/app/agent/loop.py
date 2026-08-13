"""Agent 主循环（Agentic Loop）+ 系统提示词。

v3 打磨（依据 docs/agent-definition.md）：
- 系统提示 = API 文档：动态注入完整工具手册
- Actor-Critic：工具执行由 Critic validator 验证
- 安全护栏：red 级工具需用户确认（pending confirmation）
- 上下文管理：按 token 预算截断历史（非按条数）
"""
from __future__ import annotations

import datetime
import json
import time
from typing import Any, Callable

from ..llm.base import LLMProvider
from .memory.preferences import MemoryStore
from .memory.sessions import SessionStore
from .tools.registry import ToolRegistry

MAX_STEPS = 10
CONTEXT_BUDGET = 24000  # 历史+当前消息的 token 预算（给系统提示和工具结果留余量）
MAX_TOOL_OUTPUT = 2000  # 单条工具结果限长
SUMMARIZE_THRESHOLD = 12  # 消息超过该数时建议生成摘要


def _estimate_tokens(text: str) -> int:
    """粗略 token 估算：中英文混合按 1 字符 ≈ 0.6 token，4 字符保底。"""
    return max(4, int(len(text) * 0.6))


def build_system_prompt(memory: MemoryStore, tools: ToolRegistry, status: dict[str, Any], sessions: SessionStore | None = None) -> str:
    today = datetime.date.today().isoformat()
    prefs = memory.all()
    rules = memory.list_rules()
    pref_lines = "\n".join(f"- {k}: {v}" for k, v in prefs.items()) or "(无)"
    rule_lines = "\n".join(f"- {r}" for r in rules) or "(无)"
    llm_info = json.dumps(status.get("llm") or "未配置", ensure_ascii=False)
    tool_manual = tools.manual()
    past = sessions.recent_summaries() if sessions else "(无历史会话)"

    return f"""你是「Agent Drive」的主 Agent（File Concierge）—— 一个以 AI 为中心的私人网盘的管家。

## 身份
用户的所有文件都是你的"知识资产"：你理解、组织、关联它们，随时取用。你不是聊天机器人，是能安全做事的管家。

## 跨会话记忆（历史会话摘要）
以下是之前会话的摘要，帮助你记住用户做过什么、关心什么。新会话中用户可能继续相关话题：
{past}

## 当前状态
- 今天日期: {today}（理解"今天/明天/明年"等相对时间请以此为准）
- LLM: {llm_info}
- 用户偏好: {pref_lines}
- 自动化规则: {rule_lines}

## 工具手册（API 文档）
使用工具前先读对应条目：用法、参数、输出格式、错误情况。不要臆造参数。
{tool_manual}

## 行为准则（可靠性）
1. **工具优先**：获取真实信息用工具，不凭空猜测。
2. **一致性**：同类请求用同类工具和参数；不制造与上次矛盾的操作。
3. **鲁棒性**：文件可能不存在/为空/特殊字符，先验证再操作；找不到就说找不到。
4. **透明**：动手前一句话说明要做什么；关键操作展示给用户。
5. **安全（删除流程）**：要删除文件/文件夹时，**直接调用 delete_file 工具**，系统会自动暂停并向用户请求确认，不要用文本询问用户。yellow 级操作直接执行但要说明。
6. **优雅失败**：工具返回 {{ok:false, error}} 时，先读懂错误，能修则重试，不能修就明确告诉用户。
7. **简洁**：回答用用户偏好的语言（preferences.language 默认中文），直接有用，不啰嗦。
"""


class AgentLoop:
    def __init__(
        self,
        llm: LLMProvider,
        tools: ToolRegistry,
        memory: MemoryStore,
        audit: Callable[[str], None] | None = None,
        sessions: SessionStore | None = None,
    ):
        self.llm = llm
        self.tools = tools
        self.memory = memory
        self.audit = audit or (lambda msg: None)
        self.sessions = sessions

    def _build_messages(self, user_message: str, history: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
        """上下文管理：系统提示 + 按 token 预算截断的历史 + 当前消息。"""
        status = {"llm": None}
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": build_system_prompt(self.memory, self.tools, status, self.sessions)}
        ]
        # 反向截断：从最新的历史开始，直到预算耗尽
        budget = CONTEXT_BUDGET
        selected: list[dict[str, Any]] = []
        for h in reversed(history or []):
            if h.get("role") in ("user", "assistant") and h.get("content"):
                cost = _estimate_tokens(str(h.get("content", "")))
                if budget - cost < 0 and selected:
                    break  # 预算不足且已有内容
                budget -= cost
                selected.append(h)
        messages.extend(reversed(selected))
        messages.append({"role": "user", "content": user_message})
        return messages

    async def run(
        self,
        user_message: str,
        history: list[dict[str, Any]] | None = None,
        confirmations: list[dict[str, Any]] | None = None,
        session_id: str | None = None,
    ) -> dict[str, Any]:
        """运行一轮 Agent。

        confirmations: 用户已确认的高风险操作列表 [{tool, arguments}]。
        red 级工具未确认时返回 pending_confirmation，不执行。
        session_id: 多会话持久化；None 则仅当配置了 SessionStore 时自动创建。
        """
        # ---- 多会话：创建/获取会话 ----
        sid = session_id
        if self.sessions is not None:
            if sid is None or self.sessions.get(sid) is None:
                meta = self.sessions.create()
                sid = meta["id"]
            self.sessions.append(sid, {"role": "user", "content": user_message, "ts": time.time()})

        messages = self._build_messages(user_message, history)
        confirmed = confirmations or []
        tool_trace: list[dict[str, Any]] = []
        pending: dict[str, Any] | None = None
        started = time.time()

        for step in range(MAX_STEPS):
            result = await self.llm.chat(messages, tools=self.tools.specs())

            if not result.tool_calls:
                reply = result.content or ""
                if self.sessions is not None and sid:
                    self.sessions.append(sid, {"role": "assistant", "content": reply, "ts": time.time()})
                    # 消息多了建议摘要（前端据此触发 summarize）
                    meta = self.sessions.get(sid)
                    needs_summary = (meta.get("message_count", 0) >= SUMMARIZE_THRESHOLD
                                     and not meta.get("summary"))
                else:
                    needs_summary = False
                return {
                    "reply": reply,
                    "tool_trace": tool_trace,
                    "steps": step + 1,
                    "latency_ms": int((time.time() - started) * 1000),
                    "session_id": sid,
                    "needs_summary": needs_summary,
                }

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
                if tool is not None and tool.level == "red":
                    already_confirmed = any(
                        c.get("tool") == tc.name and c.get("arguments") == tc.arguments
                        for c in confirmed
                    )
                    if not already_confirmed:
                        pending = {
                            "tool": tc.name,
                            "arguments": tc.arguments,
                            "message": f"Agent 请求执行高风险操作：{tc.name}({json.dumps(tc.arguments, ensure_ascii=False)})",
                        }
                        self.audit(f"[pending-confirm:{tc.name}] {json.dumps(tc.arguments, ensure_ascii=False)}")
                        return {
                            "reply": result.content or "",
                            "tool_trace": tool_trace,
                            "steps": step + 1,
                            "latency_ms": int((time.time() - started) * 1000),
                            "pending_confirmation": pending,
                            "session_id": sid,
                        }

                self.audit(f"[tool:{tc.name}] {json.dumps(tc.arguments, ensure_ascii=False)}")
                output = await self.tools.execute(tc.name, tc.arguments)
                if len(output) > MAX_TOOL_OUTPUT:
                    output = output[:MAX_TOOL_OUTPUT] + "\n...[截断]"
                tool_trace.append({
                    "step": step + 1,
                    "tool": tc.name,
                    "arguments": tc.arguments,
                    "output": output[:500],
                })
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc.id,
                    "content": output,
                })

        return {
            "reply": "已达到最大执行步数，任务可能未完成。请简化需求或检查配置。",
            "tool_trace": tool_trace,
            "steps": MAX_STEPS,
            "latency_ms": int((time.time() - started) * 1000),
            "truncated": True,
            "session_id": sid,
        }

    async def summarize_session(self, session_id: str) -> dict[str, Any]:
        """用 LLM 生成会话摘要（跨会话记忆核心）。

        摘要提取：用户关心的话题、完成的事情、重要的偏好/事实。
        """
        if self.sessions is None:
            return {"ok": False, "error": "未启用会话存储"}
        meta = self.sessions.get(session_id)
        if meta is None:
            return {"ok": False, "error": "会话不存在"}
        msgs = self.sessions.messages(session_id)
        transcript = "\n".join(
            f"{'用户' if m['role']=='user' else 'Agent'}: {str(m.get('content',''))[:300]}"
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

