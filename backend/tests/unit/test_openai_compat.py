"""openai_compat.py 纯函数测试：_convert_messages 与 _to_openai_tools 的协议转换。"""
from __future__ import annotations

import json

from app.llm.base import ToolSpec
from app.llm.providers.openai_compat import OpenAICompatProvider


# ---------- _convert_messages ----------

def test_convert_tool_message():
    out = OpenAICompatProvider._convert_messages([
        {"role": "tool", "tool_call_id": "call_1", "content": "42"},
    ])
    assert out == [{"role": "tool", "tool_call_id": "call_1", "content": "42"}]


def test_convert_tool_message_defaults_empty():
    out = OpenAICompatProvider._convert_messages([{"role": "tool"}])
    assert out == [{"role": "tool", "tool_call_id": "", "content": ""}]


def test_convert_assistant_with_tool_calls():
    """assistant.tool_calls → OpenAI tool_calls 格式，arguments 序列化为 JSON 字符串。"""
    messages = [{
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {"id": "c1", "name": "read_file", "arguments": {"path": "a.txt"}},
            {"id": "c2", "name": "write_file", "arguments": {"path": "b", "content": "你好"}},
        ],
        "extra": "ignored",
    }]
    out = OpenAICompatProvider._convert_messages(messages)
    assert len(out) == 1
    msg = out[0]
    assert msg["role"] == "assistant"
    assert msg["content"] == ""  # None 归一为 ""
    assert len(msg["tool_calls"]) == 2

    tc0 = msg["tool_calls"][0]
    assert tc0["id"] == "c1"
    assert tc0["type"] == "function"
    assert tc0["function"]["name"] == "read_file"
    assert json.loads(tc0["function"]["arguments"]) == {"path": "a.txt"}

    # 中文参数 ensure_ascii=False（人类可读，不转义）
    tc1 = msg["tool_calls"][1]
    assert "你好" in tc1["function"]["arguments"]
    assert json.loads(tc1["function"]["arguments"]) == {"path": "b", "content": "你好"}


def test_convert_assistant_plain_content():
    out = OpenAICompatProvider._convert_messages([{"role": "assistant", "content": "hi there"}])
    assert out == [{"role": "assistant", "content": "hi there"}]


def test_convert_system_and_user_passthrough():
    out = OpenAICompatProvider._convert_messages([
        {"role": "system", "content": "you are helpful"},
        {"role": "user", "content": "hello"},
    ])
    assert out == [
        {"role": "system", "content": "you are helpful"},
        {"role": "user", "content": "hello"},
    ]


def test_convert_unknown_keys_dropped():
    """内部格式额外字段（如 name）不进入 OpenAI 消息。"""
    out = OpenAICompatProvider._convert_messages([{"role": "user", "content": "x", "name": "z"}])
    assert "name" not in out[0]


# ---------- _to_openai_tools ----------

def test_to_openai_tools_empty():
    assert OpenAICompatProvider._to_openai_tools([]) == []


def test_to_openai_tools_shape():
    tools = [
        ToolSpec(name="read_file", description="读文件", parameters={
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        }),
        ToolSpec(name="no_params", description="无参数工具"),
    ]
    out = OpenAICompatProvider._to_openai_tools(tools)
    assert len(out) == 2
    assert out[0]["type"] == "function"
    assert out[0]["function"]["name"] == "read_file"
    assert out[0]["function"]["description"] == "读文件"
    assert out[0]["function"]["parameters"]["required"] == ["path"]

    # 无 parameters 时给空 schema
    assert out[1]["function"]["parameters"] == {"type": "object", "properties": {}}
