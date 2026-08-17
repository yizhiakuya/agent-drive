"""Agent 通用后端接口工具：从 OpenAPI 自发现并调用 HTTP 与内部兼容能力。"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx

from ...core.logging import redact_text, redact_value
from ...llm.base import ToolSpec
from ...storage.local import LocalStorage
from .registry import ToolRegistry

_API_PREFIX = "/api/v1/"
_EXCLUDED_PREFIXES = ("/api/v1/auth", "/api/v1/chat")
_EXCLUDED_PATHS = frozenset({"/api/v1/health"})
_READ_ONLY_POSTS = frozenset({"/api/v1/config/models"})
_DISCOVERY_LIMIT = 6
_RESPONSE_LIMIT = 5000

# 将常见中文意图映射到接口路径中的英文词，避免模型必须先把中文翻译成路径关键词。
_DISCOVERY_ALIASES = {
    "文件": ("file", "files"),
    "移动": ("move", "rename"),
    "复制": ("copy",),
    "删除": ("delete", "trash"),
    "恢复": ("restore",),
    "上传": ("upload",),
    "下载": ("download", "raw"),
    "预览": ("raw", "info"),
    "配置": ("config",),
    "模型": ("model", "llm"),
    "任务": ("task", "rebuild", "cleanup"),
    "索引": ("index", "rebuild", "cleanup"),
    "设备": ("device",),
    "会话": ("session",),
    "自动化": ("automation",),
    "嵌入": ("embedding",),
    "记忆": ("memory",),
    "全文": ("search_content", "content", "search"),
    "语义": ("semantic_search", "embedding", "search"),
    "规则": ("rule", "automation"),
    "偏好": ("preference", "settings"),
    "审计": ("audit", "failure"),
    "创建": ("create", "add", "post"),
    "查询": ("get", "list", "status", "info"),
    "设置": ("config", "update", "put"),
}


@dataclass(frozen=True)
class ApiOperation:
    key: str
    operation_id: str
    method: str
    path: str
    summary: str
    description: str
    parameters: tuple[dict[str, Any], ...]
    body_schema: dict[str, Any] | None
    media_type: str | None
    risk: str
    source: str = "http"
    legacy_name: str | None = None

    def public(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "operation": self.key,
            "summary": self.summary,
            "risk": self.risk,
        }
        if self.parameters:
            result["parameters"] = list(self.parameters)
        if self.body_schema is not None:
            result["body"] = {"media_type": self.media_type, "schema": self.body_schema}
        return result


class BackendApiClient:
    """在当前 FastAPI 应用内调用已认证接口，并适配无 HTTP 路由的内部能力。"""

    def __init__(
        self,
        app: Any,
        request: Any | None = None,
        storage: LocalStorage | None = None,
        internal: bool = False,
        legacy_registry: ToolRegistry | None = None,
    ) -> None:
        self.app = app
        self.internal = internal
        self.storage = storage
        self.legacy_registry = legacy_registry
        self.cookies = dict(getattr(request, "cookies", {}) or {})
        authorization = getattr(request, "headers", {}).get("authorization", "") if request is not None else ""
        self.headers = {"authorization": authorization} if authorization else {}
        if internal and app is not None:
            container = getattr(getattr(app, "state", None), "container", None)
            token = getattr(container, "internal_api_token", "")
            if token:
                self.headers["x-agent-internal-token"] = token
        self._operations = self._load_operations()
        if self.legacy_registry is not None:
            self._operations = (*self._operations, *self._load_legacy_operations())
        self._by_key = {op.key: op for op in self._operations}
        self._by_operation_id: dict[str, ApiOperation] = {}
        for operation in self._operations:
            if operation.source == "http":
                self._by_operation_id.setdefault(operation.operation_id, operation)

    @property
    def operations(self) -> tuple[ApiOperation, ...]:
        return self._operations

    def _load_operations(self) -> tuple[ApiOperation, ...]:
        if self.app is None or not hasattr(self.app, "openapi"):
            return ()
        document = self.app.openapi()
        components = document.get("components", {}).get("schemas", {})
        operations: list[ApiOperation] = []
        for path, path_item in document.get("paths", {}).items():
            if not path.startswith(_API_PREFIX) or path in _EXCLUDED_PATHS:
                continue
            if any(path.startswith(prefix) for prefix in _EXCLUDED_PREFIXES):
                continue
            for method, raw in path_item.items():
                if method.lower() not in {"get", "post", "put", "patch", "delete"} or not isinstance(raw, dict):
                    continue
                verb = method.upper()
                key = f"{verb} {path}"
                params = tuple(
                    self._parameter_summary(param, components)
                    for param in raw.get("parameters", [])
                    if isinstance(param, dict)
                )
                body_schema, media_type = self._body_summary(raw.get("requestBody"), components)
                operation_id = str(raw.get("operationId") or key.lower().replace(" ", "_"))
                description = str(raw.get("description") or "").strip()
                summary = str(raw.get("summary") or path).strip()
                operations.append(
                    ApiOperation(
                        key=key,
                        operation_id=operation_id,
                        method=verb,
                        path=path,
                        summary=summary,
                        description=description[:400],
                        parameters=params,
                        body_schema=body_schema,
                        media_type=media_type,
                        risk=self._risk_for(verb, path),
                    )
                )
        return tuple(sorted(operations, key=lambda op: (op.path, op.method)))

    def _load_legacy_operations(self) -> tuple[ApiOperation, ...]:
        if self.legacy_registry is None:
            return ()
        operations: list[ApiOperation] = []
        for spec in self.legacy_registry.specs():
            tool = self.legacy_registry.get(spec.name)
            if tool is None:
                continue
            operations.append(
                ApiOperation(
                    key=f"INTERNAL {spec.name}",
                    operation_id=spec.name,
                    method="INTERNAL",
                    path=spec.name,
                    summary=spec.description[:160],
                    description=(spec.doc or spec.description)[:400],
                    parameters=(),
                    body_schema=spec.parameters or None,
                    media_type="application/json",
                    risk=tool.level,
                    source="legacy",
                    legacy_name=spec.name,
                )
            )
        return tuple(operations)

    @classmethod
    def _risk_for(cls, method: str, path: str) -> str:
        if method == "GET" or path in _READ_ONLY_POSTS:
            return "green"
        if path.endswith(("/test", "/models")):
            return "yellow"
        return "red"

    def risk_for(self, arguments: dict[str, Any]) -> str:
        if arguments.get("action", "discover") != "call":
            return "green"
        operation = self._find_operation(arguments.get("operation", ""))
        if operation is None:
            return "green"
        legacy_name = operation.legacy_name or ""
        is_delete = (
            operation.method == "DELETE"
            or legacy_name.startswith("delete_")
            or legacy_name == "empty_trash"
        )
        if self.internal and operation.risk == "red" and not is_delete and "/trash" not in operation.path:
            return "yellow"
        return operation.risk

    @staticmethod
    def _resolve_schema(schema: Any, components: dict[str, Any], depth: int = 0) -> Any:
        if depth > 3 or not isinstance(schema, dict):
            return schema
        if "$ref" in schema:
            name = str(schema["$ref"]).rsplit("/", 1)[-1]
            return BackendApiClient._resolve_schema(components.get(name, {}), components, depth + 1)
        if "anyOf" in schema:
            return {"anyOf": [BackendApiClient._resolve_schema(item, components, depth + 1) for item in schema["anyOf"]]}
        result: dict[str, Any] = {}
        for key in ("type", "title", "description", "required", "enum", "default", "format"):
            if key in schema:
                result[key] = schema[key]
        if "properties" in schema:
            result["properties"] = {
                name: BackendApiClient._resolve_schema(value, components, depth + 1)
                for name, value in schema["properties"].items()
            }
        if "items" in schema:
            result["items"] = BackendApiClient._resolve_schema(schema["items"], components, depth + 1)
        return result or {"type": "object"}

    @classmethod
    def _parameter_summary(cls, parameter: dict[str, Any], components: dict[str, Any]) -> dict[str, Any]:
        schema = cls._resolve_schema(parameter.get("schema", {}), components)
        return {
            "name": parameter.get("name", ""),
            "in": parameter.get("in", "query"),
            "required": bool(parameter.get("required", False)),
            "schema": schema,
        }

    @classmethod
    def _body_summary(
        cls,
        request_body: Any,
        components: dict[str, Any],
    ) -> tuple[dict[str, Any] | None, str | None]:
        if not isinstance(request_body, dict):
            return None, None
        content = request_body.get("content", {})
        if not isinstance(content, dict) or not content:
            return None, None
        media_type = next(iter(content))
        media = content.get(media_type, {})
        return cls._resolve_schema(media.get("schema", {}), components), media_type

    def _find_operation(self, value: str) -> ApiOperation | None:
        return self._by_key.get(value) or self._by_operation_id.get(value)

    def discover(self, query: str = "") -> dict[str, Any]:
        query = (query or "").strip().lower()
        if not query:
            ranked = sorted(self._operations, key=lambda op: (op.source != "http", op.path, op.method))
        else:
            terms = [query]
            for alias, expansions in _DISCOVERY_ALIASES.items():
                if alias in query:
                    terms.extend(expansions)
            ranked_with_score: list[tuple[int, ApiOperation]] = []
            for operation in self._operations:
                haystack = f"{operation.key} {operation.operation_id} {operation.summary} {operation.description}".lower()
                score = sum((len(term) + 1) for term in terms if term and term in haystack)
                if score:
                    ranked_with_score.append((score, operation))
            ranked = [
                op
                for _score, op in sorted(
                    ranked_with_score,
                    key=lambda item: (-item[0], item[1].source != "http", item[1].path, item[1].method),
                )
            ]
        results = [operation.public() for operation in ranked[:_DISCOVERY_LIMIT]]
        return {
            "ok": True,
            "action": "discover",
            "query": query,
            "total_matches": len(ranked),
            "operations": results,
            "hint": "使用返回的 operation 字段调用 action=call；HTTP operation 形如 METHOD /api/v1/path，内部兼容 operation 形如 INTERNAL name。",
        }

    @staticmethod
    def _render_path(template: str, path_params: dict[str, Any]) -> tuple[str | None, str | None]:
        missing = [name for name in re.findall(r"{([^}]+)}", template) if name not in path_params]
        if missing:
            return None, f"缺少路径参数: {', '.join(missing)}"
        unknown = set(path_params) - set(re.findall(r"{([^}]+)}", template))
        if unknown:
            return None, f"多余路径参数: {', '.join(sorted(unknown))}"
        path = template
        for name, value in path_params.items():
            path = path.replace("{" + name + "}", quote(str(value), safe=""))
        return path, None

    async def call(
        self,
        operation: str,
        path_params: dict[str, Any] | None = None,
        query: dict[str, Any] | None = None,
        body: Any = None,
        files: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        selected = self._find_operation(operation)
        if selected is None:
            suggestions = self.discover(operation).get("operations", [])
            if not suggestions:
                suggestions = self.discover().get("operations", [])
            return {
                "ok": False,
                "error": "找不到接口，请先使用 action=discover 搜索可用 operation。",
                "suggestions": suggestions,
            }
        if selected.source == "legacy":
            return await self._call_legacy(selected, path_params, query, body, files)

        path, path_error = self._render_path(selected.path, path_params or {})
        if path_error:
            return {"ok": False, "operation": selected.key, "error": path_error}
        assert path is not None
        query = query or {}
        request_kwargs: dict[str, Any] = {"params": query}
        handles = []
        try:
            if selected.media_type == "application/json":
                if body is not None:
                    request_kwargs["json"] = body
            elif selected.media_type == "application/x-www-form-urlencoded":
                request_kwargs["data"] = body or {}
            elif selected.media_type == "multipart/form-data":
                request_kwargs["data"] = body or {}
                multipart: dict[str, Any] = {}
                for field, relative_path in (files or {}).items():
                    if self.storage is None:
                        return {"ok": False, "operation": selected.key, "error": "当前请求没有文件空间上下文"}
                    file_path = self.storage.resolve(relative_path)
                    if not file_path.is_file():
                        return {"ok": False, "operation": selected.key, "error": f"文件不存在: {relative_path}"}
                    handle = file_path.open("rb")
                    handles.append(handle)
                    multipart[field] = (Path(relative_path).name, handle)
                request_kwargs["files"] = multipart
            elif body is not None:
                request_kwargs["json"] = body

            status_code = 0
            content_type = ""
            filename = ""
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=self.app),
                base_url="http://agent.internal",
                headers=self.headers,
                cookies=self.cookies,
                timeout=45.0,
                follow_redirects=False,
            ) as client, client.stream(selected.method, path, **request_kwargs) as response:
                status_code = response.status_code
                content_type = response.headers.get("content-type", "")
                filename = response.headers.get("content-disposition", "")
                if "json" in content_type:
                    raw = await response.aread()
                    try:
                        payload = httpx.Response(200, content=raw).json()
                    except ValueError:
                        payload = raw.decode(response.encoding or "utf-8", errors="replace")
                elif "text" in content_type or "event-stream" in content_type:
                    raw = await response.aread()
                    payload = raw.decode(response.encoding or "utf-8", errors="replace")[:_RESPONSE_LIMIT]
                else:
                    byte_count = 0
                    async for chunk in response.aiter_bytes():
                        byte_count += len(chunk)
                    payload = {
                        "content_type": content_type,
                        "bytes": byte_count,
                        "filename": filename,
                    }
            if status_code >= 400:
                return {
                    "ok": False,
                    "operation": selected.key,
                    "status_code": status_code,
                    "error": redact_value(payload),
                }
            return {
                "ok": True,
                "operation": selected.key,
                "status_code": status_code,
                "result": redact_value(payload),
            }
        except (OSError, httpx.HTTPError, ValueError) as exc:
            return {
                "ok": False,
                "operation": selected.key,
                "error": redact_text(f"{type(exc).__name__}: {exc}"),
            }
        finally:
            for handle in handles:
                handle.close()

    async def _call_legacy(
        self,
        operation: ApiOperation,
        path_params: dict[str, Any] | None,
        query: dict[str, Any] | None,
        body: Any,
        files: dict[str, str] | None,
    ) -> dict[str, Any]:
        if self.legacy_registry is None or operation.legacy_name is None:
            return {"ok": False, "operation": operation.key, "error": "内部兼容操作不可用"}
        if files:
            return {"ok": False, "operation": operation.key, "error": "内部操作不接受 files 参数"}
        if body is not None and not isinstance(body, dict):
            return {"ok": False, "operation": operation.key, "error": "内部操作的 body 必须是对象"}
        arguments: dict[str, Any] = {}
        arguments.update(path_params or {})
        arguments.update(query or {})
        arguments.update(body or {})
        raw = await self.legacy_registry.execute(operation.legacy_name, arguments)
        try:
            payload: Any = json.loads(raw)
        except (TypeError, ValueError):
            payload = raw
        payload = redact_value(payload)
        if isinstance(payload, dict) and payload.get("ok") is False:
            return {"operation": operation.key, **payload}
        return {"ok": True, "operation": operation.key, "result": payload}


async def _backend_api(
    client: BackendApiClient,
    action: str = "discover",
    query: str = "",
    operation: str = "",
    path_params: dict[str, Any] | None = None,
    query_params: dict[str, Any] | None = None,
    body: Any = None,
    files: dict[str, str] | None = None,
) -> dict[str, Any]:
    if action == "discover":
        return client.discover(query)
    if action == "call":
        return await client.call(operation, path_params, query_params, body, files)
    return {"ok": False, "error": "action 只能是 discover 或 call"}


def build_internal_app(container: Any) -> Any:
    """为没有 HTTP 请求的 Worker 创建只存在于进程内的 API 应用。"""
    from fastapi import FastAPI

    from ...api.v1.router import api_v1

    app = FastAPI(title="Agent Drive internal gateway")
    app.state.container = container
    app.include_router(api_v1)
    return app


def register_backend_api_tool(reg: ToolRegistry, client: BackendApiClient) -> None:
    async def call_backend_api(
        action: str = "discover",
        query: str = "",
        operation: str = "",
        path_params: dict[str, Any] | None = None,
        query_params: dict[str, Any] | None = None,
        body: Any = None,
        files: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        return await _backend_api(client, action, query, operation, path_params, query_params, body, files)

    reg.register(
        ToolSpec(
            "backend_api",
            "通用项目后端接口：先发现接口，再调用 operation。",
            {
                "type": "object",
                "properties": {
                    "action": {"type": "string", "enum": ["discover", "call"], "default": "discover"},
                    "query": {"type": "string", "description": "要完成的功能或接口关键词，如‘移动文件’、‘重建索引’"},
                    "operation": {"type": "string", "description": "discover 返回的 operation 标识"},
                    "path_params": {"type": "object", "additionalProperties": {"type": "string"}},
                    "query_params": {"type": "object", "additionalProperties": True},
                    "body": {"type": "object", "description": "JSON 或表单字段；按 discover 返回的 schema 填写", "additionalProperties": True},
                    "files": {
                        "type": "object",
                        "description": "multipart 字段到网盘内相对文件路径的映射",
                        "additionalProperties": {"type": "string"},
                    },
                },
                "required": ["action"],
                "additionalProperties": False,
            },
            doc=(
                "用途：发现并调用 Agent Drive 的业务后端接口。\n"
                "流程：先 action=discover，query 写用户意图；再用返回的 operation 调 action=call。\n"
                "参数：path_params 填路径变量，query_params 填查询参数，body 填 JSON/表单字段；上传时 files 的值是网盘内相对路径。\n"
                "限制：HTTP 只能访问已登记的 /api/v1 业务接口；未暴露 HTTP 路由的既有能力以 INTERNAL operation 提供，不能访问认证、聊天递归、外部 URL 或任意 Python 入口。\n"
                "安全：查询接口自动执行；会修改数据的接口会暂停并请求用户确认。\n"
                "错误：返回 {ok:false, status_code, error}，不要在失败后盲目重复写操作。"
            ),
        ),
        call_backend_api,
        level="dynamic",
        risk_fn=client.risk_for,
        group="backend_api",
    )
