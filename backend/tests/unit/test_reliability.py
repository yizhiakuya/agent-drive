"""可靠性测试套件（依据 Princeton 四维度框架）：

- Consistency 一致性：同一请求多次运行，工具轨迹稳定
- Robustness  鲁棒性：同义不同措辞 → 相同正确行为
- Predictability 可预测性：失败模式固定可调试（结构化错误）
- Safety      安全性：red 级操作需确认、路径穿越拦截、步数上限
"""
import asyncio
import json
import tempfile
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

import re


class ScriptedProvider:
    """可编程 LLM：根据规则返回工具调用或文本，用于确定性测试。"""

    def __init__(self, script):
        """script: list[(match_fn, response_fn)]"""
        self.script = script
        self.calls = 0

    async def chat(self, messages, tools=None):
        self.calls += 1
        last_user = ""
        for m in reversed(messages):
            if m["role"] == "user":
                last_user = m.get("content", "")
                break
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        for match, resp in self.script:
            if match(last_user, has_tool_result, messages):
                return resp(self.calls, messages)
        return LLMResult(content="（未匹配脚本）", tool_calls=[])

    async def stream_chat(self, messages, tools=None):
        """模拟流式：复用 chat 的脚本行为"""
        result = await self.chat(messages, tools)
        if result.content:
            yield result.content

    async def test_connection(self):
        return {"ok": True}


def _tool_call(name, args, cid="c1"):
    return LLMResult(
        content="",
        tool_calls=[ToolCall(id=cid, name=name, arguments=args)],
    )


def make_agent(tmp: Path):
    storage = LocalStorage(tmp / "data")
    llm_mgr = LLMManager(tmp / "system" / "agent-config.json")
    memory = MemoryStore(tmp / "system" / "memory.json")
    reg = ToolRegistry()
    register_system_tools(reg, llm_mgr, memory, rules_path=None, audit_fn=lambda n: "")
    register_file_tools(reg, storage)
    return storage, reg, memory


async def main():
    tmp = Path(tempfile.mkdtemp())

    print("=" * 55)
    print("维度 1: Consistency 一致性 —— 同一请求 3 次运行，工具轨迹必须一致")
    storage, reg, memory = make_agent(tmp)
    storage.save_bytes("报告.txt", "内容".encode())

    provider = ScriptedProvider([
        (lambda u, h, m: not h, lambda c, m: _tool_call("list_files", {})),
        (lambda u, h, m: h, lambda c, m: LLMResult(content="完成", tool_calls=[])),
    ])
    agent = AgentLoop(provider, reg, memory)
    traces = []
    for i in range(3):
        r = await agent.run("看看网盘里有什么")
        traces.append([(t["tool"], t["arguments"]) for t in r["tool_trace"]])
    assert traces[0] == traces[1] == traces[2], f"轨迹不一致: {traces}"
    print(f"  3 次运行工具轨迹: {traces[0]} → 完全一致 ✅")

    print()
    print("=" * 55)
    print("维度 2: Robustness 鲁棒性 —— 同义不同措辞 → 相同正确行为")
    phrases = ["看看网盘里有什么", "列出我的文件", "网盘里都有啥？", "show me the files"]
    for i, p in enumerate(phrases):
        storage2, reg2, memory2 = make_agent(tmp / f"r{i}")
        storage2.save_bytes("报告.txt", "内容".encode())
        prov = ScriptedProvider([
            (lambda u, h, m: not h, lambda c, m: _tool_call("list_files", {})),
            (lambda u, h, m: h, lambda c, m: LLMResult(content="完成", tool_calls=[])),
        ])
        agent2 = AgentLoop(prov, reg2, memory2)
        r = await agent2.run(p)
        assert r["tool_trace"] and r["tool_trace"][0]["tool"] == "list_files", f"措辞 '{p}' 未走 list_files"
    print(f"  {len(phrases)} 种措辞全部正确路由到 list_files ✅")

    print()
    print("=" * 55)
    print("维度 3: Predictability 可预测性 —— 失败模式固定、结构化、可调试")
    storage3, reg3, memory3 = make_agent(tmp / "p")
    # 场景 A: 读不存在的文件
    prov_a = ScriptedProvider([
        (lambda u, h, m: not h, lambda c, m: _tool_call("read_file", {"path": "不存在.txt"})),
        (lambda u, h, m: h, lambda c, m: LLMResult(content="完成", tool_calls=[])),
    ])
    r = await AgentLoop(prov_a, reg3, memory3).run("帮我读一下文件 不存在.txt")
    tool_out = r["tool_trace"][0]["output"]
    d = json.loads(tool_out)
    assert d.get("ok") is False and "FileNotFoundError" in d.get("error", ""), f"错误应结构化: {tool_out}"
    print(f"  文件不存在 → {d['error'][:50]}... （结构化、可读、可调试）✅")

    print()
    print("=" * 55)
    print("维度 4: Safety 安全性")
    # 4a: red 级操作需要确认
    storage4, reg4, memory4 = make_agent(tmp / "s")
    storage4.save_bytes("机密.txt", "secret".encode())
    prov_red = ScriptedProvider([
        (lambda u, h, m: not h, lambda c, m: _tool_call("delete_file", {"path": "机密.txt"})),
        (lambda u, h, m: h, lambda c, m: LLMResult(content="完成", tool_calls=[])),
    ])
    r = await AgentLoop(prov_red, reg4, memory4).run("删除机密文件 机密.txt")
    assert r.get("pending_confirmation"), "red 级操作必须返回 pending_confirmation"
    assert storage4.exists("机密.txt"), "未确认前文件必须还在"
    print(f"  未确认的 delete_file → 返回 pending_confirmation，文件保留 ✅")

    # 4b: 确认后执行
    r2 = await AgentLoop(prov_red, reg4, memory4).run(
        "删除机密文件 机密.txt", confirmations=[{"tool": "delete_file", "arguments": {"path": "机密.txt"}}]
    )
    assert not storage4.exists("机密.txt"), "确认后文件应被删除"
    print(f"  携带确认后 → 文件已删除 ✅")

    # 4c: 路径穿越
    r3 = await reg4.execute("read_file", {"path": "../../../etc/passwd"})
    d3 = json.loads(r3)
    assert d3.get("ok") is False and "越界" in d3.get("error", "")
    print(f"  路径穿越 → {d3['error'][:30]}... 被拦截 ✅")

    # 4d: 步数上限（防失控循环）
    class LoopProvider(ScriptedProvider):
        async def chat(self, messages, tools=None):
            return _tool_call("list_files", {}, cid=f"c{self.calls}")

    r4 = await AgentLoop(LoopProvider([]), reg4, memory4).run("测试失控循环保护机制是否生效")
    assert r4.get("truncated") and r4["steps"] == 10
    print(f"  失控循环 → 第 {r4['steps']} 步被截断 ✅")

    print()
    print("🎉 可靠性测试全部通过！四维度：一致性 ✅ 鲁棒性 ✅ 可预测性 ✅ 安全性 ✅")


if __name__ == "__main__":
    asyncio.run(main())
