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
