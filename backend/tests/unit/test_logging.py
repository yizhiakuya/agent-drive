"""日志系统单测：JSON 格式/脱敏/审计轮转/多进程锁/统一出口。"""
from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path

import pytest

from app.core.logging import (
    _ROOT_MARKER,
    AuditLogger,
    JsonFormatter,
    _RequestIdFilter,
    redact_text,
    redact_value,
    request_id_var,
    setup_logging,
)


def _make_record(name: str = "agent_drive.tasks", msg: str = "hello %s",
                 args: tuple = ("world",), level: int = logging.INFO, data=None) -> logging.LogRecord:
    rec = logging.LogRecord(name, level, __file__, 42, msg, args, None)
    if data is not None:
        rec.data = data
    return rec


# ---- 脱敏 ----

def test_redact_text_masks_sensitive_kv():
    out = redact_text('token=abc123 password="p@ss" {"api_key": "sk-secret"} ok=1')
    assert "abc123" not in out
    assert "p@ss" not in out
    assert "sk-secret" not in out
    assert "***" in out
    assert "ok=1" in out


def test_redact_text_masks_bare_tokens():
    """无键名上下文的裸 API Key（用户直接贴进聊天/记忆）也要脱敏。"""
    out = redact_text("配置向量 jina_abcdefghijklm 和 sk-abcd1234efgh5678 试试")
    assert "jina_abcdefghijklm" not in out
    assert "sk-abcd1234efgh5678" not in out
    assert "***" in out
    # 短字符串不误伤
    assert redact_text("jina_abc sk-xy") == "jina_abc sk-xy"


def test_redact_value_masks_by_key_and_recurses():
    data = {"api_key": "sk-x", "nested": {"Authorization": "Bearer y"}, "items": ["password=z"], "fine": "ok"}
    out = redact_value(data)
    assert out["api_key"] == "***"
    assert out["nested"]["Authorization"] == "***"
    assert out["items"] == ["password=***"]
    assert out["fine"] == "ok"


def test_audit_redact_alias():
    assert AuditLogger.redact("api_key=sk-1") == "api_key=***"


# ---- JSON 格式 ----

def test_json_formatter_fields_and_exc():
    fmt = JsonFormatter()
    try:
        raise ValueError("boom")
    except ValueError:
        rec = _make_record(msg="task failed", args=(), level=logging.ERROR,
                           data={"job": 1, "api_key": "sk-1"})
        rec.exc_info = sys.exc_info()
    out = json.loads(fmt.format(rec))
    assert out["level"] == "ERROR"
    assert out["logger"] == "agent_drive.tasks"
    assert out["at"].endswith(":42")
    assert out["msg"] == "task failed"
    assert out["data"]["job"] == 1
    assert out["data"]["api_key"] == "***"
    assert "sk-1" not in json.dumps(out)
    assert "ValueError" in out["exc"]


def test_json_formatter_rid_from_context():
    fmt = JsonFormatter()
    filt = _RequestIdFilter()
    tok = request_id_var.set("rid-123")
    try:
        rec = _make_record()
        assert filt.filter(rec) is True
        assert rec.rid == "rid-123"
        out = json.loads(fmt.format(rec))
        assert out["rid"] == "rid-123"
    finally:
        request_id_var.reset(tok)


def test_json_formatter_no_rid_outside_request():
    fmt = JsonFormatter()
    filt = _RequestIdFilter()
    rec = _make_record()
    filt.filter(rec)
    out = json.loads(fmt.format(rec))
    assert "rid" not in out


# ---- setup_logging 统一出口 ----

def test_setup_logging_idempotent_and_uvicorn_neutralized():
    root = logging.getLogger()
    setup_logging("prod")
    count = len(root.handlers)
    setup_logging("prod")
    assert len(root.handlers) == count
    assert any(getattr(h, _ROOT_MARKER, False) for h in root.handlers)
    for name in ("uvicorn", "uvicorn.error"):
        lg = logging.getLogger(name)
        assert lg.handlers == []
        assert lg.propagate is True
    # uvicorn.access 必须断开传播：否则 hasHandlers() 沿父链看到 root handler，
    # uvicorn 会自己再记一条访问日志（--no-access-log 对该版本无效）。
    # 只断言 uvicorn 自己的 handler 已清空——pytest 的 caplog 可能注入 live
    # handler（不影响生产：生产无 caplog 时 hasHandlers() 为 False）
    access = logging.getLogger("uvicorn.access")
    assert access.propagate is False
    assert [h for h in access.handlers if "pytest" not in type(h).__module__] == []


def test_child_loggers_reach_single_root_exit():
    setup_logging("prod")
    handler = next(h for h in logging.getLogger().handlers if getattr(h, _ROOT_MARKER, False))
    rec = _make_record(name="agent_drive.tasks.sub")
    handler.filter(rec)
    out = handler.format(rec)
    assert "hello world" in out


# ---- 审计日志 ----

def test_audit_record_redact_tail_and_lockfile(tmp_path: Path):
    audit = AuditLogger(tmp_path / "audit.log")
    audit.record('[tool:set_llm_provider] {"type": "openai_compat", "api_key": "sk-secret123"}',
                 result="token=abc")
    text = (tmp_path / "audit.log").read_text(encoding="utf-8")
    assert "sk-secret123" not in text
    assert "token=abc" not in text
    assert "***" in text
    line = json.loads(text.strip())
    assert set(line) == {"ts", "event", "result"}
    assert line["result"] == "token=***"
    assert audit.tail(1)
    assert (tmp_path / "audit.log.lock").exists()


def test_audit_rotate(tmp_path: Path):
    audit = AuditLogger(tmp_path / "audit.log")
    audit.MAX_BYTES = 1  # 任何已有内容都触发轮转，便于断言
    audit.record("event-one")
    audit.record("event-two")
    rotated = json.loads((tmp_path / "audit.log.1").read_text(encoding="utf-8"))
    current = json.loads((tmp_path / "audit.log").read_text(encoding="utf-8"))
    assert rotated["event"] == "event-one"
    assert current["event"] == "event-two"


def test_audit_failures_filter(tmp_path: Path):
    audit = AuditLogger(tmp_path / "audit.log")
    audit.record("ok-event")
    audit.record("tool-error", result="boom")
    audit.record("pending-confirm: x")
    fails = audit.failures()
    assert [f["event"] for f in fails] == ["tool-error", "pending-confirm: x"]


def _audit_writer(path_str: str, tag: str, n: int) -> None:
    audit = AuditLogger(Path(path_str))
    for i in range(n):
        audit.record(f"proc-{tag}", result={"i": i})


def test_audit_multiprocess_lock(tmp_path: Path):
    """两个进程并发追加：flock 保证无撕裂行、无丢失。"""
    if os.name == "nt":
        pytest.skip("Windows 无 fcntl，不在该平台测多进程锁")
    from multiprocessing import get_context

    path = tmp_path / "audit.log"
    n = 40
    ctx = get_context("spawn")
    procs = [ctx.Process(target=_audit_writer, args=(str(path), t, n)) for t in ("A", "B")]
    for p in procs:
        p.start()
    for p in procs:
        p.join()
        assert p.exitcode == 0
    lines = path.read_text(encoding="utf-8").splitlines()
    assert len(lines) == n * 2
    for line in lines:
        json.loads(line)  # 每行都是完整 JSON（无撕裂）
