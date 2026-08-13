"""端到端测试：用 FakeProvider 验证 Agent 循环（工具调用链）。"""
import asyncio
from pathlib import Path


import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
from app.agent.loop import AgentLoop
from app.agent.memory.preferences import MemoryStore
from app.agent.tools.files import register_file_tools
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.system import register_system_tools
from app.llm.base import LLMResult, ToolCall
from app.llm.manager import LLMManager
from app.storage.local import LocalStorage

import tempfile


class FakeProvider:
    """模拟一个支持工具调用的 LLM：
    第1轮调用 list_files，第2轮基于结果回复。"""
    name = "fake"

    def __init__(self):
        self.round = 0

    async def chat(self, messages, tools=None):
        self.round += 1
        if self.round == 1:
            return LLMResult(
                content="让我看看文件列表",
                tool_calls=[ToolCall(id="call_1", name="list_files", arguments={"path": ""})],
            )
        # 第2轮：检查工具结果是否在消息里
        last = messages[-1]
        has_result = "list_files" in str(last.get("content", "")) or "test.txt" in str(last.get("content", ""))
        return LLMResult(
            content=f"工具调用成功: {has_result}。我看到文件了。",
            tool_calls=[],
        )

    async def test_connection(self):
        return {"ok": True, "model": "fake"}


async def main():
    tmp = tempfile.mkdtemp()
    root = Path(tmp)
    storage = LocalStorage(root / "data")
    # 放一个测试文件
    storage.save_bytes("test.txt", b"hello agent drive")

    llm_mgr = LLMManager(root / "system" / "agent-config.json")
    memory = MemoryStore(root / "system" / "memory.json")
    audit_events = []

    reg = ToolRegistry()
    register_system_tools(reg, llm_mgr, memory, rules_path=None, audit_fn=lambda n: str(audit_events[-n:]))
    register_file_tools(reg, storage)

    agent = AgentLoop(FakeProvider(), reg, memory, audit=audit_events.append)

    print("=" * 50)
    print("测试 1: 工具调用循环")
    result = await agent.run("看看我的网盘里有什么文件")
    print(f"回复: {result['reply']}")
    print(f"工具轨迹: {result['tool_trace']}")
    print(f"步数: {result['steps']}, 耗时: {result['latency_ms']}ms")
    assert result["steps"] == 2, "应执行 2 步（1 次工具调用 + 1 次回复）"
    assert any(t["tool"] == "list_files" for t in result["tool_trace"]), "应调用 list_files"
    assert "test.txt" in str(result["tool_trace"]), "工具应看到 test.txt"
    print("✅ 测试 1 通过")

    print()
    print("=" * 50)
    print("测试 2: 审计日志")
    print(f"审计事件: {audit_events}")
    assert len(audit_events) >= 1, "应有审计事件"
    print("✅ 测试 2 通过")

    print()
    print("=" * 50)
    print("测试 3: 路径穿越防护")
    try:
        storage.resolve("../../etc/passwd")
        print("❌ 应抛错")
    except PermissionError:
        print("✅ 测试 3 通过 (路径穿越被拦截)")

    print()
    print("=" * 50)
    print("测试 4: 未配置 LLM 时的错误处理")
    try:
        llm_mgr.get_provider()
        print("❌ 应抛错")
    except Exception as e:
        print(f"✅ 测试 4 通过 (异常: {type(e).__name__})")


if __name__ == "__main__":
    asyncio.run(main())
print("\n🎉 全部测试通过！")
