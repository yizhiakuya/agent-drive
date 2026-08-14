"""上下文压缩测试：token 计数、自动压缩、轮内压缩、工具检索。"""
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
try:  # Windows 控制台 GBK：强制 UTF-8 输出，避免 ✅/中文打印崩溃
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from app.agent.context import (
    compress_history,
    compress_tool_roundtrips,
    count_messages_tokens,
    estimate_tokens,
    build_history,
)
from app.agent.router import classify
from app.llm.base import LLMResult


class FakeLLM:
    """压缩测试用：返回固定摘要"""
    def __init__(self):
        self.calls = 0

    async def chat(self, messages, tools=None):
        self.calls += 1
        return LLMResult(content="摘要：用户关注量子计算论文，要求整理文件。")

    async def stream_chat(self, messages, tools=None):
        yield ""


async def main():
    print("=" * 55)
    print("测试 1: tiktoken 精确计数")
    n1 = estimate_tokens("hello world")
    n2 = estimate_tokens("你好，世界！这是一段中文测试")
    print(f"  'hello world' → {n1} tokens; 中文12字 → {n2} tokens")
    assert n1 == 2 and n2 > 5
    print("✅ 通过")

    print()
    print("=" * 55)
    print("测试 2: 历史预算截断（超预算只保留最新）")
    history = [{"role": "user", "content": "x" * 5000}] * 10
    cut = build_history(history, context_budget=3000)
    assert len(cut) < 10 and len(cut) >= 1
    print(f"  10 条 5000 字符消息 → 预算 3000 保留 {len(cut)} 条")
    print("✅ 通过")

    print()
    print("=" * 55)
    print("测试 3: 自动压缩（超阈值 → LLM 摘要早期消息）")
    llm = FakeLLM()
    history = [
        {"role": "user", "content": "帮我整理文件 " + "y" * 3000},
        {"role": "assistant", "content": "好的 " + "z" * 3000},
        {"role": "user", "content": "继续"},
        {"role": "assistant", "content": "完成"},
    ]
    compressed, summary = await compress_history(llm, history, keep_recent=2)
    assert llm.calls == 1, "应调用 LLM 生成摘要"
    assert summary and "摘要" in summary
    assert len(compressed) == 3  # 1 条摘要 + 2 条 recent
    assert compressed[0]["role"] == "system" and "摘要" in compressed[0]["content"]
    print(f"  4 条历史 → 压缩为 3 条（摘要+最近2条），摘要: {summary[:30]}...")
    print("✅ 通过")

    print()
    print("=" * 55)
    print("测试 4: 压缩失败回退（不丢最近消息）")
    class BrokenLLM(FakeLLM):
        async def chat(self, messages, tools=None):
            raise RuntimeError("LLM down")
    history = [
        {"role": "user", "content": "a" * 100},
        {"role": "assistant", "content": "b" * 100},
        {"role": "user", "content": "c" * 100},
        {"role": "assistant", "content": "d" * 100},
    ]
    compressed, summary = await compress_history(BrokenLLM(), history, keep_recent=2)
    assert len(compressed) == 2 and summary is None
    print("  LLM 故障 → 回退滑动窗口（保留最近 2 条），不崩溃")
    print("✅ 通过")

    print()
    print("=" * 55)
    print("测试 5: 轮内工具结果压缩")
    messages = [
        {"role": "system", "content": "sys"},
        {"role": "user", "content": "hi"},
    ]
    for i in range(10):  # 10 轮工具往返
        messages.append({"role": "assistant", "content": "", "tool_calls": [{"id": f"c{i}", "name": "list_files", "arguments": {}}]})
        messages.append({"role": "tool", "tool_call_id": f"c{i}", "content": f"[{{'name': 'file{i}.txt'}}]"})
    compressed = compress_tool_roundtrips(messages, keep_roundtrips=4)
    assert len(compressed) < len(messages)
    assert any("[早期工具执行已压缩]" in m.get("content", "") for m in compressed)
    print(f"  10 轮工具往返({len(messages)}条) → 压缩为 {len(compressed)} 条")
    print("✅ 通过")

    print()
    print("=" * 55)
    print("测试 6: 工具检索（意图 → 工具组）")
    cases = [
        ("帮我找一下预算文件", "task", ["files", "plan", "skills", "memory"]),
        ("把 LLM 换成 DeepSeek", "task", ["system", "analytics", "plan", "memory"]),
        ("你好", "chat", None),
        ("分析一下这个数据", "task", None),  # 无法确定 → 全量
    ]
    for msg, expect_mode, expect_groups in cases:
        mode, groups = classify(msg)
        assert mode == expect_mode, f"{msg}: mode {mode} != {expect_mode}"
        assert groups == expect_groups, f"{msg}: groups {groups} != {expect_groups}"
        print(f"  ✅ '{msg}' → {mode} + {groups}")
    print("✅ 通过")


if __name__ == "__main__":
    asyncio.run(main())
print("\n🎉 上下文管理测试全部通过！")
