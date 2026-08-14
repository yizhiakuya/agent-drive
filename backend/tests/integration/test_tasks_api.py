"""Authenticated task API integration tests."""
from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.container import Container
from app.llm.manager import LLMConfig
from app.main import create_app


def _client(tmp_path: Path):
    container = Container(Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    ))
    client = TestClient(create_app(container))
    return client, container


def test_task_routes_require_authentication(tmp_path: Path):
    client, _ = _client(tmp_path)
    with client:
        assert client.get("/api/v1/tasks").status_code == 401
        assert client.post("/api/v1/tasks/rebuild-index", json={}).status_code == 401


def test_upload_queues_task_and_task_can_be_cancelled_and_retried(tmp_path: Path):
    client, _ = _client(tmp_path)
    with client:
        assert client.post("/api/v1/auth/setup", json={"password": "test-password-123"}).status_code == 200
        uploaded = client.post(
            "/api/v1/files/upload",
            files={"file": ("note.txt", b"hello task queue", "text/plain")},
        )
        assert uploaded.status_code == 200
        task_id = uploaded.json()["indexed"]["task_id"]

        listing = client.get("/api/v1/tasks").json()
        assert listing["items"][0]["id"] == task_id
        assert listing["overview"]["counts"]["queued"] == 1
        cancelled = client.post(f"/api/v1/tasks/{task_id}/cancel")
        assert cancelled.status_code == 200
        assert cancelled.json()["task"]["status"] == "cancelled"
        retried = client.post(f"/api/v1/tasks/{task_id}/retry")
        assert retried.status_code == 200
        assert retried.json()["task"]["status"] == "queued"


def test_rebuild_requires_embedding_configuration(tmp_path: Path):
    client, _ = _client(tmp_path)
    with client:
        client.post("/api/v1/auth/setup", json={"password": "test-password-123"})
        response = client.post("/api/v1/tasks/rebuild-index", json={"force": False})
        assert response.status_code == 409


def test_rebuild_rejects_unsafe_prefix_before_queueing(tmp_path: Path):
    client, container = _client(tmp_path)
    container.tasks.refresh_embedder = lambda: object()
    with client:
        client.post("/api/v1/auth/setup", json={"password": "test-password-123"})
        response = client.post(
            "/api/v1/tasks/rebuild-index",
            json={"prefix": "../outside", "force": False},
        )
        assert response.status_code == 400
        assert container.job_store.list_jobs(task_type="index.rebuild") == []


def test_embedding_config_rejects_missing_key_and_unknown_provider(tmp_path: Path):
    client, container = _client(tmp_path)
    container.llm.save(LLMConfig(
        type="openai_compat",
        base_url="https://llm.invalid/v1",
        api_key="llm-key",
        model="test-model",
    ))
    with client:
        client.post("/api/v1/auth/setup", json={"password": "test-password-123"})
        missing_key = client.put(
            "/api/v1/config/embeddings",
            json={"provider": "jina", "api_key": ""},
        )
        assert missing_key.status_code == 400
        unknown = client.put(
            "/api/v1/config/embeddings",
            json={"provider": "unknown", "api_key": "secret"},
        )
        assert unknown.status_code == 400
        assert container.llm.load().embeddings is None
