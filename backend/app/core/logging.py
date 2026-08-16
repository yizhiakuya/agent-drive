"""日志系统：单一入口 setup_logging + 审计日志。

职责（Container 初始化时调用 setup_logging）：
- 所有 logger（agent_drive.*、第三方库、uvicorn.*）统一走 root 上的同一个 handler：
  - prod：单行 JSON（ts/level/logger/at/msg/rid/data/exc）
  - dev/test：可读格式（含 rid）
- uvicorn CLI 会在 import 应用前配置自己的 handler（Config.__init__ →
  configure_logging），setup_logging 清掉它的私有 handler 让 uvicorn.* 记录也走
  root；生产 unit 额外用 --no-access-log，HTTP 访问日志由本模块的
  AccessLogMiddleware 结构化输出（含真实客户端 IP 与请求 ID）。
- audit：审计日志 JSONL（system/audit.log），只记 auth/Agent 操作事件；
  flock 多进程安全 + fsync；脱敏见 redact_text。

约定（与 AGENTS.md「日志约定」保持一致）：
- 业务 logger 一律 logging.getLogger("agent_drive.<子系统>")，不要用裸 __name__ 树
- 结构化字段通过 extra={"data": {...}} 挂载；文本中的敏感字段按 key 名或
  key=value 模式脱敏
- 查询用 scripts/logs.sh（journalctl + 内置过滤），审计看 audit.log 尾部
"""
from __future__ import annotations

import json
import logging
import os
import re
import sys
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from pathlib import Path
from typing import Any

try:  # Windows 开发机没有 fcntl：锁退化为无锁（单进程场景）
    import fcntl as _fcntl
except ImportError:  # pragma: no cover - Windows only
    _fcntl = None  # type: ignore[assignment]

# 请求 ID：AccessLogMiddleware 写入，格式化器/过滤器读取（"-" = 请求上下文之外）
request_id_var: ContextVar[str] = ContextVar("agent_drive_request_id", default="-")

_UVICORN_LOGGERS = ("uvicorn", "uvicorn.error", "uvicorn.access")
_ROOT_MARKER = "_agent_drive_handler"

_SENSITIVE_PARTS = ("password", "token", "secret", "key", "authorization")
_SENSITIVE_PATTERNS = tuple(
    re.compile(r'("?' + key + r'"?' + r'\s*[:=]\s*"?)([^",\s}]+)("?)', re.IGNORECASE)
    for key in ("api_key", "password", "token", "authorization", "secret", "key")
)
# 裸令牌（无键名上下文）：Jina 与 OpenAI 风格 API Key。
# 阈值 12+ 字符，避免误伤短词；用户把 key 直接贴进聊天/记忆时也拦截
_BARE_TOKEN = re.compile(r"\b(?:jina_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9_-]{12,})\b")


def redact_text(text: str) -> str:
    """脱敏：key=value / "key": "value" 形式与裸令牌（jina_/sk-）替换为 ***。"""
    for pattern in _SENSITIVE_PATTERNS:
        text = pattern.sub(r"\1***\3", text)
    text = _BARE_TOKEN.sub("***", text)
    return text


def redact_value(value: Any, key: str = "") -> Any:
    """递归脱敏：键名含敏感词直接掩码；字符串走 redact_text；容器递归。"""
    if key and any(part in key.lower() for part in _SENSITIVE_PARTS):
        return "***"
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return {k: redact_value(v, str(k)) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact_value(v) for v in value]
    return value


class _RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.rid = getattr(record, "rid", None) or request_id_var.get() or "-"
        return True


class JsonFormatter(logging.Formatter):
    """prod：单行 JSON。字段：ts/level/logger/at/msg[/rid][/data][/exc]。"""

    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, Any] = {
            "ts": round(time.time(), 3),
            "level": record.levelname,
            "logger": record.name,
            "at": f"{record.module}:{record.lineno}",
            "msg": redact_text(record.getMessage()),
        }
        rid = getattr(record, "rid", "-")
        if rid and rid != "-":
            entry["rid"] = rid
        data = getattr(record, "data", None)
        if data is not None:
            entry["data"] = redact_value(data)
        if record.exc_info:
            entry["exc"] = redact_text(self.formatException(record.exc_info))
        return json.dumps(entry, ensure_ascii=False, default=str)


def setup_logging(app_env: str = "dev") -> logging.Logger:
    """配置 root handler（幂等）。清掉 uvicorn 私有 handler，全部记录走同一出口。"""
    root = logging.getLogger()
    if any(getattr(h, _ROOT_MARKER, False) for h in root.handlers):
        return logging.getLogger("agent_drive")

    root.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stdout)
    setattr(handler, _ROOT_MARKER, True)
    if app_env == "prod":
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(logging.Formatter(
            "%(asctime)s %(levelname)-7s %(name)s | rid=%(rid)s %(message)s",
        ))
    handler.addFilter(_RequestIdFilter())
    root.addHandler(handler)

    # uvicorn CLI 先于 app import 配置过自己的 handler，清掉让记录统一走 root
    for name in _UVICORN_LOGGERS:
        lg = logging.getLogger(name)
        lg.handlers.clear()
        lg.propagate = True
        lg.setLevel(logging.NOTSET)
    # 本机 uvicorn 的 h11 协议用 uvicorn.access.hasHandlers() 决定是否自己记
    # 访问日志（hasHandlers 沿父链查找，root 有 handler 时恒为 True，
    # --no-access-log 对它无效），必须显式断开传播，访问日志统一由
    # AccessLogMiddleware 结构化输出（health 只 DEBUG，避免探活刷屏）
    access = logging.getLogger("uvicorn.access")
    access.propagate = False

    logger = logging.getLogger("agent_drive")
    if app_env == "dev":
        # dev 让项目日志打到 DEBUG，第三方库保持 root INFO 不刷屏
        logger.setLevel(logging.DEBUG)
    logger.info("logging initialized: env=%s handler=%s", app_env, "json" if app_env == "prod" else "text")
    return logger


class AccessLogMiddleware:
    """纯 ASGI 访问日志：真实 IP + 请求 ID + 状态码 + 耗时，走统一日志出口。

    main.py 里放在最外层（最后一个 add_middleware）。health 探活只记 DEBUG
    避免刷屏；不记录 query string（设备令牌走 ?token= 参数，防泄入日志）。
    异常请求记 exc + data 后原样抛出（Starlette 会再补一条 traceback 记录）。
    """

    def __init__(self, app: Any):
        self.app = app
        self.logger = logging.getLogger("agent_drive.http")

    async def __call__(self, scope: dict, receive: Any, send: Any) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        raw_rid = ""
        for name, value in scope.get("headers", []):
            if name == b"x-request-id":
                raw_rid = value.decode("latin-1", errors="replace")
                break
        rid = "".join(ch for ch in raw_rid if ch.isalnum() or ch in "-_")[:64] or uuid.uuid4().hex[:12]
        token = request_id_var.set(rid)
        start = time.perf_counter()
        status = 0

        async def send_wrapper(message: dict) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = list(message.get("headers", []))
                headers.append((b"x-request-id", rid.encode("latin-1")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
            path = scope.get("path", "")
            is_health = path == "/api/v1/health"
            data = {
                "method": scope.get("method"), "path": path, "status": status,
                "ms": round((time.perf_counter() - start) * 1000, 1),
                "ip": self._client_ip(scope),
            }
            # 注意：必须在 finally reset 之前发日志，否则 rid 上下文已丢失
            self.logger.log(
                logging.DEBUG if is_health else logging.INFO,
                "http: method=%s path=%s status=%s",
                scope.get("method"), path, status,
                extra={"data": data},
            )
        except Exception:
            self.logger.exception(
                "http failed: method=%s path=%s",
                scope.get("method"), scope.get("path"),
                extra={"data": {
                    "method": scope.get("method"), "path": scope.get("path"),
                    "status": 500, "ms": round((time.perf_counter() - start) * 1000, 1),
                    "ip": self._client_ip(scope),
                }},
            )
            raise
        finally:
            request_id_var.reset(token)

    @staticmethod
    def _client_ip(scope: dict) -> str:
        for name, value in scope.get("headers", []):
            if name == b"x-real-ip":
                return value.decode("latin-1", errors="replace")
        client = scope.get("client")
        return client[0] if client else "-"


class AuditLogger:
    """审计日志：追加 JSONL，只记录 auth/Agent 操作事件。"""

    MAX_BYTES = 1_000_000  # 1MB 轮转上限
    MAX_BACKUPS = 5        # 保留最近 5 份历史（约 6MB）

    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock_path = self.path.with_suffix(".log.lock")

    @contextmanager
    def _locked(self) -> Iterator[None]:
        """跨进程互斥（API + Worker 都可能写 audit.log）。Windows 无 fcntl 退化为无锁。"""
        with open(self._lock_path, "a", encoding="utf-8") as lf:
            try:
                Path(self._lock_path).chmod(0o600)
            except OSError:
                pass
            if _fcntl is not None:
                _fcntl.flock(lf.fileno(), _fcntl.LOCK_EX)
            try:
                yield
            finally:
                if _fcntl is not None:
                    _fcntl.flock(lf.fileno(), _fcntl.LOCK_UN)

    def _rotate(self) -> None:
        """超上限时轮转：audit.log → audit.log.1 → … → audit.log.5（更旧丢弃）。"""
        try:
            if not self.path.exists() or self.path.stat().st_size < self.MAX_BYTES:
                return
            oldest = self.path.with_suffix(f".log.{self.MAX_BACKUPS}")
            if oldest.exists():
                oldest.unlink()
            for i in range(self.MAX_BACKUPS - 1, 0, -1):
                src = self.path.with_suffix(f".log.{i}")
                if src.exists():
                    src.rename(self.path.with_suffix(f".log.{i + 1}"))
            self.path.rename(self.path.with_suffix(".log.1"))
        except OSError:
            pass

    def record(self, event: str, result: Any = None) -> None:
        line = json.dumps(
            {
                "ts": time.time(),
                "event": redact_text(event),
                "result": redact_text(str(result)) if result is not None else None,
            },
            ensure_ascii=False,
        ) + "\n"
        with self._locked():
            self._rotate()
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(line)
                f.flush()
                try:
                    os.fsync(f.fileno())
                except OSError:
                    pass

    # redact 保留类方法别名：既有测试/工具以 AuditLogger.redact 调用
    redact = staticmethod(redact_text)

    def tail(self, limit: int = 20) -> str:
        if not self.path.exists():
            return "(无审计记录)"
        lines = self.path.read_text(encoding="utf-8").splitlines()[-limit:]
        return "\n".join(lines)

    def failures(self, recent: int = 50) -> list[dict[str, Any]]:
        """提取最近失败事件（供错误分析工具使用）"""
        failures: list[dict[str, Any]] = []
        if not self.path.exists():
            return failures
        for line in self.path.read_text(encoding="utf-8").splitlines()[-recent:]:
            try:
                ev = json.loads(line)
                text = json.dumps(ev, ensure_ascii=False)
                if any(k in text for k in ("error", "pending-confirm", "fail")):
                    failures.append(ev)
            except Exception:
                continue
        return failures
