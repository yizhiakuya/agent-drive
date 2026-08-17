from types import SimpleNamespace

import pytest
from fastapi import Depends, FastAPI, Request, Response
from pydantic import BaseModel

from app.agent.tools.analytics import register_analytics_tools
from app.agent.tools.api import BackendApiClient, register_backend_api_tool
from app.agent.tools.files import register_file_tools
from app.agent.tools.memory import register_memory_tools
from app.agent.tools.registry import ToolRegistry
from app.agent.tools.system import register_system_tools
from app.api.deps import get_owner
from app.llm.base import ToolSpec


class Widget(BaseModel):
    name: str


def make_app() -> FastAPI:
    app = FastAPI()

    @app.get("/api/v1/files/{file_id}", operation_id="get_file")
    async def get_file(file_id: str, request: Request):
        return {"id": file_id, "session": request.cookies.get("agent_drive_session")}

    @app.delete("/api/v1/files/{file_id}", operation_id="delete_file")
    async def delete_file(file_id: str):
        return {"deleted": file_id}

    @app.get("/api/v1/raw", operation_id="raw_file")
    async def raw_file():
        return Response(b"binary-data", media_type="application/octet-stream", headers={"content-disposition": "attachment; filename=demo.bin"})

    @app.post("/api/v1/widgets", operation_id="create_widget")
    async def create_widget(widget: Widget):
        return {"created": widget.name}

    @app.post("/api/v1/config/models", operation_id="probe_models")
    async def probe_models():
        return {"models": ["demo"]}

    @app.post("/api/v1/auth/login", operation_id="login")
    async def login():
        return {"token": "never expose"}

    return app


@pytest.fixture
def client() -> BackendApiClient:
    request = SimpleNamespace(
        cookies={"agent_drive_session": "session-token"},
        headers={"authorization": "Bearer ignored-by-cookie"},
    )
    return BackendApiClient(make_app(), request)


def test_discover_uses_openapi_and_chinese_aliases(client: BackendApiClient):
    result = client.discover("文件")
    operations = {item["operation"] for item in result["operations"]}
    assert result["ok"] is True
    assert "GET /api/v1/files/{file_id}" in operations
    assert "DELETE /api/v1/files/{file_id}" in operations
    assert all("/auth/" not in item["operation"] for item in result["operations"])
    delete = next(item for item in result["operations"] if item["operation"].startswith("DELETE "))
    assert delete["risk"] == "red"


def test_discover_exposes_request_schema_and_operation_id(client: BackendApiClient):
    result = client.discover("创建")
    operation = next(item for item in result["operations"] if item["operation"] == "POST /api/v1/widgets")
    assert operation["body"]["media_type"] == "application/json"
    assert operation["body"]["schema"]["properties"]["name"]["type"] == "string"


@pytest.mark.asyncio
async def test_call_reuses_request_cookie_without_exposing_headers(client: BackendApiClient):
    result = await client.call(
        "GET /api/v1/files/{file_id}",
        path_params={"file_id": "file-1"},
    )
    assert result == {
        "ok": True,
        "operation": "GET /api/v1/files/{file_id}",
        "status_code": 200,
        "result": {"id": "file-1", "session": "session-token"},
    }


@pytest.mark.asyncio
async def test_binary_response_returns_metadata_without_content(client: BackendApiClient):
    result = await client.call("GET /api/v1/raw")
    assert result == {
        "ok": True,
        "operation": "GET /api/v1/raw",
        "status_code": 200,
        "result": {
            "content_type": "application/octet-stream",
            "bytes": len(b"binary-data"),
            "filename": "attachment; filename=demo.bin",
        },
    }


@pytest.mark.asyncio
async def test_call_validates_path_and_json_body(client: BackendApiClient):
    missing = await client.call("GET /api/v1/files/{file_id}")
    assert missing["ok"] is False
    assert "file_id" in missing["error"]

    created = await client.call("POST /api/v1/widgets", body={"name": "alpha"})
    assert created["ok"] is True
    assert created["result"] == {"created": "alpha"}


@pytest.mark.asyncio
async def test_unknown_operation_returns_discovery_suggestions(client: BackendApiClient):
    result = await client.call("GET /api/v1/does-not-exist")
    assert result["ok"] is False
    assert result["suggestions"]


@pytest.mark.asyncio
async def test_internal_gateway_token_passes_api_auth_without_user_cookie():
    app = FastAPI()
    app.state.container = SimpleNamespace(
        internal_api_token="worker-only-token",
        auth=SimpleNamespace(verify_session=lambda _token: False, verify_device_token=lambda _token: False),
    )

    @app.get("/api/v1/private", dependencies=[Depends(get_owner)])
    async def private_endpoint():
        return {"ok": True}

    internal = BackendApiClient(app, internal=True)
    result = await internal.call("GET /api/v1/private")
    assert result["ok"] is True


@pytest.mark.asyncio
async def test_registry_exposes_one_backend_api_tool(client: BackendApiClient):
    registry = ToolRegistry()
    register_backend_api_tool(registry, client)

    tool = registry.get("backend_api")
    assert tool is not None
    assert tool.level_for({"action": "discover"}) == "green"
    assert tool.level_for({"action": "call", "operation": "DELETE /api/v1/files/{file_id}"}) == "red"
    result = await registry.execute("backend_api", {"action": "discover", "query": "文件"})
    assert '"ok": true' in result


def test_legacy_registries_are_hidden_behind_backend_api():
    dependency = SimpleNamespace()
    legacy = ToolRegistry()
    register_file_tools(legacy, dependency)
    register_system_tools(
        legacy,
        dependency,
        dependency,
        audit_fn=lambda _message: None,
        scheduler=dependency,
        tasks=dependency,
    )
    register_memory_tools(legacy, dependency)
    register_analytics_tools(legacy, lambda: dependency, dependency, None)

    client = BackendApiClient(make_app(), legacy_registry=legacy)
    assert len(legacy.specs()) >= 30
    assert sum(operation.source == "legacy" for operation in client.operations) == len(legacy.specs())
    assert any(item["operation"] == "INTERNAL semantic_search" for item in client.discover("semantic_search")["operations"])


@pytest.mark.asyncio
async def test_compatibility_operations_share_discovery_and_call_envelope():
    legacy = ToolRegistry()

    async def remember(content: str) -> dict[str, str]:
        return {"saved": content}

    legacy.register(
        ToolSpec(
            "remember",
            "保存记忆",
            {"type": "object", "properties": {"content": {"type": "string"}}},
        ),
        remember,
        level="yellow",
    )
    client = BackendApiClient(make_app(), legacy_registry=legacy)

    discovered = client.discover("记忆")
    operation = next(item for item in discovered["operations"] if item["operation"] == "INTERNAL remember")
    assert operation["risk"] == "yellow"
    assert operation["body"]["schema"]["properties"]["content"]["type"] == "string"

    result = await client.call(operation["operation"], body={"content": "hello"})
    assert result == {
        "ok": True,
        "operation": "INTERNAL remember",
        "result": {"saved": "hello"},
    }


def test_risk_is_dynamic_per_operation(client: BackendApiClient):
    assert client.risk_for({"action": "discover"}) == "green"
    assert client.risk_for({"action": "call", "operation": "GET /api/v1/files/{file_id}"}) == "green"
    assert client.risk_for({"action": "call", "operation": "DELETE /api/v1/files/{file_id}"}) == "red"
    assert client.risk_for({"action": "call", "operation": "POST /api/v1/config/models"}) == "green"
