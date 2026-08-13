"""上下文管理（定义规范原则2：拆分上下文）。

v2 升级：
- 精确 token 计数（tiktoken，fallback 字符估算）
- 自动压缩：历史超阈值时 LLM 摘要早期消息（滚动摘要）
- 轮内压缩：工具往返过多时合并早期工具结果
"""
from __future__ import annotations

import json
from typing import Any

try:
    import tiktoken

    _ENC = tiktoken.get_encoding("cl100k_base")
    _HAS_TIKTOKEN = True
except Exception:
    _ENC = None
    _HAS_TIKTOKEN = False


def estimate_tokens(text: str) -> int:
    """token 计数：优先 tiktoken 精确计数，fallback 字符估算。"""
    if _HAS_TIKTOKEN and _ENC is not None:
        try:
            return len(_ENC.encode(text))
        except Exception:
            pass
    return max(4, int(len(text) * 0.6))


def count_messages_tokens(messages: list[dict[str, Any]]) -> int:
    """估算 messages 列表的总 token（含工具调用参数）。"""
    total = 0
    for m in messages:
        total += estimate_tokens(str(m.get("content", "")))
        for tc in m.get("tool_calls", []):
            total += estimate_tokens(str(tc.get("arguments", ""))) + 8
        total += 4  # 每条消息的格式开销
    return total


def try_parse_json(text: str):
    """尝试把工具输出解析为结构化对象（前端渲染用）。"""
    try:
        return json.loads(text)
    except Exception:
        return None


def build_history(
    history: list[dict[str, Any]] | None,
    context_budget: int,
) -> list[dict[str, Any]]:
    """按 token 预算从最新历史反向截断。

    修复 R5（memory-review #3）：放行带"[早期对话摘要]"标记的 system 消息，
    滚动压缩的 LLM 摘要不再被静默丢弃。
    """
    selected: list[dict[str, Any]] = []
    budget = context_budget
    for h in reversed(history or []):
        content = str(h.get("content", ""))
        is_summary_system = (
            h.get("role") == "system" and content.startswith("[早期对话摘要]")
        )
        if (h.get("role") in ("user", "assistant") and content) or is_summary_system:
            cost = estimate_tokens(content)
            if budget - cost < 0 and selected:
                break
            budget -= cost
            selected.append(h)
    return list(reversed(selected))


# ---------- 自动压缩 ----------

def summarize_prompt(transcript: str, prev_summary: str | None = None) -> str:
    """生成压缩摘要的提示词。"""
    prev = f"\n\n以下是更早对话的已有摘要（不要重复其细节，只在其基础上增量补充新信息）：\n{prev_summary}" if prev_summary else ""
    return (
        "请把以下对话压缩成一份要点摘要（中文，300 字以内），保留：\n"
        "1) 用户的偏好/习惯/重要事实\n"
        "2) 已完成的文件和系统操作\n"
        "3) 未完成的事项和用户的最新意图\n"
        f"{prev}\n\n对话记录:\n{transcript}"
    )


async def compress_history(
    llm,
    history: list[dict[str, Any]],
    keep_recent: int,
    prev_summary: str | None = None,
) -> tuple[list[dict[str, Any]], str | None]:
    """压缩历史：LLM 摘要早期消息，保留最近 keep_recent 条。

    返回: (压缩后的历史, 新摘要)。压缩后的历史以 system 消息开头携带摘要。
    """
    if len(history) <= keep_recent:
        return history, prev_summary
    old, recent = history[:-keep_recent], history[-keep_recent:]
    transcript = "\n".join(
        f"{'用户' if m['role'] == 'user' else 'Agent'}: {str(m.get('content', ''))[:200]}"
        for m in old[-40:]
    )
    try:
        result = await llm.chat([{"role": "user", "content": summarize_prompt(transcript, prev_summary)}])
        summary = (result.content or "").strip()
    except Exception:
        # 压缩失败：退回"丢弃早期消息"策略（滑动窗口仍可用）
        return recent, prev_summary
    if not summary:
        return recent, prev_summary
    summary_msg = {"role": "system", "content": f"[早期对话摘要] {summary}"}
    return [summary_msg] + recent, summary


def compress_tool_roundtrips(
    messages: list[dict[str, Any]],
    keep_roundtrips: int = 4,
) -> list[dict[str, Any]]:
    """轮内压缩：工具往返过多时，把早期往返合并为一行摘要。

    修复 R2：
    - 压缩单元 = 完整 roundtrip（assistant(tool_calls) + 其全部 tool 结果），
      切点只落在 assistant 消息边界，绝不产生孤儿 tool 消息。
    - 摘要放在压缩区最前面，且不插在对话中间破坏协议结构
      （摘要内容合并进"保留区第一条消息"，不新增中间 system）。
    """
    # 找出所有 assistant(tool_calls) 消息的下标（每个往返的起点）
    roundtrip_starts = [
        i for i, m in enumerate(messages)
        if m.get("role") == "assistant" and m.get("tool_calls")
    ]
    if len(roundtrip_starts) <= keep_roundtrips:
        return messages

    # 保留最近 keep_roundtrips 个完整往返（从它们的起点切）
    cut = roundtrip_starts[-keep_roundtrips]
    old_part, keep_part = messages[:cut], messages[cut:]

    # 压缩摘要：工具名 + 参数 + 结果提示
    brief = []
    for m in old_part:
        if m.get("role") == "assistant" and m.get("tool_calls"):
            for tc in m.get("tool_calls", []):
                brief.append(f"{tc.get('name')}({str(tc.get('arguments', {}))[:40]})")
        elif m.get("role") == "tool":
            out = str(m.get("content", ""))[:50].replace("\n", " ")
            brief.append(f"→ {out}")
    summary = "[早期工具执行已压缩] " + " | ".join(brief[-10:])[:800]

    # 摘要并入保留区开头：作为 user 消息前缀（不破坏角色结构）
    keep_part[0] = {
        **keep_part[0],
        "content": f"{summary}\n\n{keep_part[0].get('content', '')}",
    }
    return keep_part
