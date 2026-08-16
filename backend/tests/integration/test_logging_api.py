"""访问日志集成测试：请求 ID 透传 + 结构化 access 记录。"""
from __future__ import annotations

import logging
from pathlib import Path

from fastapi.testclient import TestClient

from app.core.config import Settings
from app.core.container import Container
from app.main import create_app


def _make_client(tmp_path: Path) -> TestClient:
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    return TestClient(app)


def test_request_id_echo_and_structured_access_log(tmp_path: Path, caplog):
    """health 探活：响应回显 X-Request-ID，且产生带 rid 的 DEBUG 访问日志。"""
    client = _make_client(tmp_path)
    http_logger = logging.getLogger("agent_drive.http")
    old_level = http_logger.level
    http_logger.setLevel(logging.DEBUG)  # health 只记 DEBUG，需临时放开
    try:
        with client, caplog.at_level(logging.DEBUG):
            r = client.get("/api/v1/health", headers={"X-Request-ID": "qa-rid-42"})
        assert r.status_code == 200
        assert r.headers["x-request-id"] == "qa-rid-42"
        recs = [
            rec for rec in caplog.records
            if rec.name == "agent_drive.http"
            and getattr(rec, "data", {}).get("path") == "/api/v1/health"
        ]
        assert recs, "health 请求应产生结构化访问日志"
        last = recs[-1]
        assert last.data["status"] == 200
        assert last.data["method"] == "GET"
        assert getattr(last, "rid", None) == "qa-rid-42"
    finally:
        http_logger.setLevel(old_level)


def test_access_log_records_unauthorized(tmp_path: Path, caplog):
    """未登录请求：401 也应有结构化访问日志（INFO 级，无需改日志级别）。"""
    client = _make_client(tmp_path)
    with client, caplog.at_level(logging.INFO):
        r = client.get("/api/v1/files")
    assert r.status_code == 401
    recs = [
        rec for rec in caplog.records
        if rec.name == "agent_drive.http"
        and getattr(rec, "data", {}).get("path") == "/api/v1/files"
    ]
    assert recs, "401 请求应产生访问日志"
    assert recs[-1].data["status"] == 401
