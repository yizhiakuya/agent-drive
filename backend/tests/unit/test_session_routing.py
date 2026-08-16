"""会话上下文路由回归测试（pytest 风格）。

生产 bug：任务会话中用户发短消息（"继续/确认/随便"，<10 字），classify 判为
chat → 无工具路径 → 模型纯文本"假装"调工具（实测零工具执行、配置未写入）。
修复：会话上一轮走过任务路径（meta.last_routed=="task"，旧格式回退 last_trace）
时，短消息续接必须走任务路径（全量工具）。
"""
import pytest

from app.agent.loop import AgentLoop
from app.agent.memory.preferences import MemoryStore
from app.agent.memory.sessions import SessionStore
from app.agent.tools.files import register_file_tools
from app.agent.tools.registry import ToolRegistry
from app.llm.base import LLMResult, ToolCall
from app.storage.local import LocalStorage


class ScriptedProvider:
    """带 tools 且无工具结果时返回一次 list_files 调用；其余返回纯文本。"""

    def __init__(self) -> None:
        self.tool_rounds = 0

    async def chat(self, messages, tools=None):
        if tools:
            has_tool_result = any(m.get("role") == "tool" for m in messages)
            if not has_tool_result:
                self.tool_rounds += 1
                return LLMResult(
                    content="",
                    tool_calls=[ToolCall(id="c1", name="list_files", arguments={"path": ""})],
                )
        return LLMResult(content="好的。", tool_calls=[])

    async def stream_chat(self, messages, tools=None):
        r = await self.chat(messages, tools)
        if r.content:
            yield r.content


@pytest.fixture
def env(tmp_path):
    storage = LocalStorage(tmp_path / "data")
    storage.save_bytes("a.txt", b"x")
    sessions = SessionStore(tmp_path / "system" / "sessions")
    memory = MemoryStore(tmp_path / "system" / "memory.json")
    reg = ToolRegistry()
    register_file_tools(reg, storage)
    return storage, sessions, memory, reg


def make_loop(provider, sessions, memory, reg):
    return AgentLoop(provider, reg, memory, sessions=sessions, max_steps=4)


@pytest.mark.asyncio
async def test_short_message_in_fresh_session_stays_chat(env):
    _, sessions, memory, reg = env
    provider = ScriptedProvider()
    loop = make_loop(provider, sessions, memory, reg)
    result = await loop.run("继续")
    assert result["routed"] == "chat"
    assert provider.tool_rounds == 0


@pytest.mark.asyncio
async def test_short_continuation_in_task_session_runs_tools(env):
    """任务会话中"继续"必须走任务路径并执行工具（生产回归）。"""
    _, sessions, memory, reg = env
    provider = ScriptedProvider()
    loop = make_loop(provider, sessions, memory, reg)

    first = await loop.run("看看我的网盘里有什么文件")
    assert first["routed"] == "task"
    assert provider.tool_rounds == 1
    sid = first["session_id"]
    assert sid

    second = await loop.run("继续", session_id=sid)
    assert second["routed"] == "task", "任务会话中短消息被降级为 chat（工具不执行）"
    assert provider.tool_rounds == 2, "续接轮应再次调用工具"
    assert any(t["tool"] == "list_files" for t in second["tool_trace"])


@pytest.mark.asyncio
async def test_chat_session_short_message_stays_chat(env):
    """纯闲聊会话的短消息仍走 chat（不误升级）。"""
    _, sessions, memory, reg = env
    provider = ScriptedProvider()
    loop = make_loop(provider, sessions, memory, reg)

    first = await loop.run("你好")
    assert first["routed"] == "chat"
    sid = first["session_id"]
    assert sid

    second = await loop.run("在吗", session_id=sid)
    assert second["routed"] == "chat"
    assert provider.tool_rounds == 0


@pytest.mark.asyncio
async def test_legacy_session_with_last_trace_routes_task(env):
    """旧格式会话（无 last_routed 字段，只有 last_trace）续接仍走任务路径。"""
    _, sessions, memory, reg = env
    provider = ScriptedProvider()
    loop = make_loop(provider, sessions, memory, reg)

    first = await loop.run("看看我的网盘里有什么文件")
    sid = first["session_id"]
    # 模拟旧格式：抹掉 last_routed，仅保留 last_trace
    sessions.update_meta(sid, last_routed=None)

    second = await loop.run("随便", session_id=sid)
    assert second["routed"] == "task"
    assert provider.tool_rounds == 2


@pytest.mark.asyncio
async def test_chat_path_marks_last_routed(env):
    _, sessions, memory, reg = env
    provider = ScriptedProvider()
    loop = make_loop(provider, sessions, memory, reg)
    result = await loop.run("你好")
    sid = result["session_id"]
    meta = sessions.get(sid)
    assert meta is not None and meta.get("last_routed") == "chat"
