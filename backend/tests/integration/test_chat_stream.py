"""集成测试：POST /api/v1/chat/stream 的 SSE 线上契约。

契约：每个 data 行必须是 JSON 对象；text 事件形状 {"text": str}（裸字符串会被前端解析器拒绝）。
"""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.container import Container
from app.main import create_app


class _FakeAgent:
    async def run_stream(self, message, history, confirmations, session_id):
        yield ("text", "你好")
        yield ("text", "世界")
        yield ("done", {"session_id": "s1", "plan": [], "context_usage": {}, "usage": {}, "reply": "你好世界"})


@pytest.fixture
def client(tmp_path: Path):
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        assert c.post("/api/v1/auth/setup", json={"password": "test-password-123"}).status_code == 200
        container.build_agent = lambda: _FakeAgent()
        yield c


def test_chat_stream_text_events_are_json_objects(client) -> None:
    r = client.post(
        "/api/v1/chat/stream",
        json={"message": "hi", "history": [], "confirmations": [], "session_id": None},
    )
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/event-stream")
    body = r.text
    # 契约断言：text 事件必须是对象包裹，绝不允许裸字符串 data
    assert 'event: text\ndata: {"text": "你好"}' in body
    assert 'event: text\ndata: {"text": "世界"}' in body
    assert 'data: "你好"' not in body
    assert 'data: "世界"' not in body
    assert "event: done" in body


class _ExplodingAgent:
    async def run_stream(self, message, history, confirmations, session_id):
        yield ("text", "部分输出")
        raise RuntimeError("模拟 LLM 崩溃")


def test_chat_stream_error_event_branch(client) -> None:
    """run_stream 抛异常时，SSE 应推送 event: error，data 为 JSON 对象，含 error 字段。"""
    # 替换容器 build_agent 返回会抛异常的 agent
    client.app.state.container.build_agent = lambda: _ExplodingAgent()
    r = client.post(
        "/api/v1/chat/stream",
        json={"message": "hi", "history": [], "confirmations": [], "session_id": None},
    )
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/event-stream")
    body = r.text
    assert "event: error" in body
    # data 仍是 JSON 对象（线上契约），且含 error 字段
    assert "模拟 LLM 崩溃" in body
    assert 'data: {"error":' in body  # 错误也必须是对象包裹，不是裸字符串
