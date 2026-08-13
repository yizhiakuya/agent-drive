"""API 集成测试（pytest 风格）：验证 v1 路由 + Container 组装。"""
from __future__ import annotations

import tempfile
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.container import Container
from app.main import create_app


@pytest.fixture
def client(tmp_path: Path):
    """用临时目录 + 测试容器启动应用（不污染真实数据）"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        yield c, container


def test_root_status(client):
    c, _ = client
    r = c.get("/")
    assert r.status_code == 200
    assert r.json()["name"] == "Agent Drive"


def test_status_not_configured(client):
    c, _ = client
    r = c.get("/api/v1/status")
    assert r.status_code == 200
    assert r.json()["configured"] is False


def test_files_upload_and_list(client):
    c, _ = client
    # 上传
    r = c.post("/api/v1/files/upload", files={"file": ("测试.txt", b"hello", "text/plain")})
    assert r.status_code == 200
    # 列表
    r = c.get("/api/v1/files")
    assert r.status_code == 200
    items = r.json()["items"]
    assert any(i["name"] == "测试.txt" for i in items)


def test_files_path_traversal_blocked(client):
    c, _ = client
    r = c.get("/api/v1/files/download", params={"path": "../../../etc/passwd"})
    assert r.status_code == 403


def test_chat_requires_config(client):
    c, _ = client
    r = c.post("/api/v1/chat", json={"message": "你好", "history": []})
    assert r.status_code == 400  # 未配置 LLM
    assert "LLM" in r.json()["detail"] or "Onboarding" in r.json()["detail"]


def test_sessions_crud(client):
    c, container = client
    # 创建（通过 chat 自动创建需要 LLM；直接调 SessionStore）
    meta = container.sessions.create()
    sid = meta["id"]
    container.sessions.append(sid, {"role": "user", "content": "hi"})
    # 列表
    r = c.get("/api/v1/sessions")
    assert r.status_code == 200
    assert any(s["id"] == sid for s in r.json()["sessions"])
    # 详情
    r = c.get(f"/api/v1/sessions/{sid}")
    assert r.status_code == 200
    assert len(r.json()["messages"]) == 1
    # 删除
    r = c.delete(f"/api/v1/sessions/{sid}")
    assert r.status_code == 200
    r = c.get(f"/api/v1/sessions/{sid}")
    assert r.status_code == 404
