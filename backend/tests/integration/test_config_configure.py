"""集成测试：POST /api/v1/config 保存 LLM 配置（key 留空回退语义）。"""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.container import Container
from app.llm.manager import LLMConfig
from app.main import create_app


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
        yield c, container


def _fake_test(captured: dict):
    async def fake(cfg):
        captured["cfg"] = cfg
        return {"ok": True}

    return fake


def test_configure_key_fallback_when_type_and_base_url_match(client) -> None:
    c, container = client
    container.llm.save(
        LLMConfig(type="openai_compat", base_url="http://stored", api_key="sk-stored", model="m")
    )
    captured: dict = {}
    container.llm.test = _fake_test(captured)
    r = c.post(
        "/api/v1/config",
        json={"type": "openai_compat", "base_url": "http://stored", "api_key": "", "model": "m2"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert captured["cfg"].api_key == "sk-stored"
    saved = container.llm.load()
    assert saved is not None and saved.model == "m2" and saved.api_key == "sk-stored"


def test_configure_requires_key_when_base_url_differs(client) -> None:
    c, container = client
    container.llm.save(
        LLMConfig(type="openai_compat", base_url="http://stored", api_key="sk-stored", model="m")
    )
    called: dict = {}
    container.llm.test = _fake_test(called)
    r = c.post(
        "/api/v1/config",
        json={"type": "openai_compat", "base_url": "http://other", "api_key": "", "model": "m"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is False
    assert "API Key 为空" in body["error"]
    assert "cfg" not in called  # 未拿旧 key 打向新地址


def test_configure_explicit_key_no_fallback(client) -> None:
    c, container = client
    captured: dict = {}
    container.llm.test = _fake_test(captured)
    r = c.post(
        "/api/v1/config",
        json={"type": "anthropic", "base_url": "http://a", "api_key": "sk-new", "model": "c"},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert captured["cfg"].api_key == "sk-new"


def test_configure_empty_key_without_stored_config(client) -> None:
    c, container = client
    called: dict = {}
    container.llm.test = _fake_test(called)
    r = c.post(
        "/api/v1/config",
        json={"type": "openai_compat", "base_url": "http://x", "api_key": "", "model": "m"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is False
    assert "API Key 为空" in body["error"]
    assert "cfg" not in called
