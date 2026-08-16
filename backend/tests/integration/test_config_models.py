"""集成测试：POST /api/v1/config/models（模型列表探测端点）。"""
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


class _FakeProvider:
    def __init__(self, models: list[str]) -> None:
        self._models = models

    async def list_models(self) -> list[str]:
        return self._models


def _patch(container, models: list[str]) -> dict:
    captured: dict = {}

    def build(cfg):
        captured["cfg"] = cfg
        return _FakeProvider(models)

    container.llm.build = build  # 实例级替换 staticmethod 调用点
    return captured


def test_models_with_explicit_key(client) -> None:
    c, container = client
    captured = _patch(container, ["a", "b"])
    r = c.post(
        "/api/v1/config/models",
        json={"type": "openai_compat", "base_url": "http://x", "api_key": "sk-explicit"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["models"] == ["a", "b"]
    assert captured["cfg"].api_key == "sk-explicit"


def test_models_key_fallback_when_form_matches_stored(client) -> None:
    c, container = client
    container.llm.save(
        LLMConfig(type="openai_compat", base_url="http://stored", api_key="sk-stored", model="m")
    )
    captured = _patch(container, ["x"])
    r = c.post(
        "/api/v1/config/models",
        json={"type": "openai_compat", "base_url": "http://stored", "api_key": ""},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is True
    assert captured["cfg"].api_key == "sk-stored"


def test_models_key_not_leaked_when_base_url_differs(client) -> None:
    c, container = client
    container.llm.save(
        LLMConfig(type="openai_compat", base_url="http://stored", api_key="sk-stored", model="m")
    )
    _patch(container, ["x"])
    r = c.post(
        "/api/v1/config/models",
        json={"type": "openai_compat", "base_url": "http://other", "api_key": ""},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is False
    assert "API Key 为空" in body["error"]


def test_models_unknown_provider_type(client) -> None:
    c, container = client
    r = c.post(
        "/api/v1/config/models",
        json={"type": "nope", "base_url": "http://x", "api_key": "k"},
    )
    assert r.status_code == 200
    assert r.json()["ok"] is False
    assert "未知 Provider 类型" in r.json()["error"]


def test_models_requires_auth(tmp_path: Path) -> None:
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        r = c.post(
            "/api/v1/config/models",
            json={"type": "openai_compat", "base_url": "http://x", "api_key": "k"},
        )
        assert r.status_code == 401
