"""日志系统：三流分离。

- app_logger: 应用日志（结构化 JSON in prod，可读 in dev）
- audit: 审计日志（追加到 system/audit.log，Agent 操作追踪）
- session: 会话记录（由 SessionStore 管理）
"""
from __future__ import annotations

import json
import logging
import sys
import time
from pathlib import Path
from typing import Any


def setup_logging(app_env: str = "dev") -> logging.Logger:
    logger = logging.getLogger("agent_drive")
    if logger.handlers:
        return logger
    level = logging.DEBUG if app_env == "dev" else logging.INFO
    logger.setLevel(level)
    handler = logging.StreamHandler(sys.stdout)
    if app_env == "prod":
        class JsonFormatter(logging.Formatter):
            def format(self, record: logging.LogRecord) -> str:
                return json.dumps({
                    "ts": time.time(),
                    "level": record.levelname,
                    "logger": record.name,
                    "msg": record.getMessage(),
                }, ensure_ascii=False)
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)-7s %(name)s | %(message)s"))
    logger.addHandler(handler)
    return logger


class AuditLogger:
    """审计日志：追加 JSONL，只记录 Agent 操作事件。"""

    MAX_BYTES = 1_000_000  # 1MB 轮转上限

    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def _rotate(self) -> None:
        """超上限时轮转：audit.log → audit.log.1"""
        if not self.path.exists() or self.path.stat().st_size < self.MAX_BYTES:
            return
        backup = self.path.with_suffix(".log.1")
        try:
            if backup.exists():
                backup.unlink()
            self.path.rename(backup)
        except OSError:
            pass

    SENSITIVE_KEYS = ("api_key", "password", "token", "authorization", "secret", "key")

    @classmethod
    def redact(cls, text: str) -> str:
        """脱敏：把 api_key/password/token 等字段值替换为 ***"""
        import re
        # 处理 JSON 风格 "key": "value" 和 key=value 两种形式
        for key in cls.SENSITIVE_KEYS:
            pattern = re.compile(
                r'("?' + key + r'"?' + r'\s*[:=]\s*"?)([^",\s}]+)("?)',
                re.IGNORECASE,
            )
            text = pattern.sub(r"\1***\3", text)
        return text

    def record(self, event: str, result: Any = None) -> None:
        self._rotate()
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(json.dumps(
                {
                    "ts": time.time(),
                    "event": self.redact(event),
                    "result": self.redact(str(result)) if result else None,
                },
                ensure_ascii=False,
            ) + "\n")

    def tail(self, limit: int = 20) -> str:
        if not self.path.exists():
            return "(无审计记录)"
        lines = self.path.read_text(encoding="utf-8").splitlines()[-limit:]
        return "\n".join(lines)

    def failures(self, recent: int = 50) -> list[dict[str, Any]]:
        """提取最近失败事件（供错误分析工具使用）"""
        failures = []
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
