"""统一异常体系。

- AppError 基类：所有业务异常继承
- API 层：转换为 HTTP 响应
- Agent 层：转换为结构化工具结果 {ok:false, error}
"""
from __future__ import annotations


class AppError(Exception):
    status_code = 400
    code = "app_error"

    def __init__(self, message: str, *, detail: dict | None = None):
        super().__init__(message)
        self.message = message
        self.detail = detail or {}

    def to_dict(self) -> dict:
        return {"error": self.code, "message": self.message, **self.detail}


class ConfigError(AppError):
    status_code = 400
    code = "config_error"


class AuthError(AppError):
    status_code = 401
    code = "auth_error"


class NotFoundError(AppError):
    status_code = 404
    code = "not_found"


class PermissionError(AppError):
    status_code = 403
    code = "permission_denied"


class ToolError(AppError):
    """工具执行失败：Agent 可读的结构化错误"""
    status_code = 422
    code = "tool_error"


class LLMError(AppError):
    status_code = 502
    code = "llm_error"

    def __init__(self, message: str, *, kind: str = "unknown", detail: dict | None = None):
        super().__init__(message, detail=detail)
        self.kind = kind  # timeout | protocol | quota | auth | unknown
        self.detail = {**(detail or {}), "kind": kind}
