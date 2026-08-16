"""core/retry.py pytest 化测试（瞬态重试次数、永久错误立即失败、退避序列、on_retry 回调）。"""
from __future__ import annotations

import asyncio

import pytest

from app.core.retry import is_retryable_error, with_retry


@pytest.mark.asyncio
async def test_transient_retries_then_success():
    calls = 0

    async def flaky():
        nonlocal calls
        calls += 1
        if calls < 3:
            raise TimeoutError("request timed out")
        return "ok"

    result = await with_retry(flaky, max_retries=2, base_delay=0.01, jitter=False)
    assert result == "ok"
    assert calls == 3  # 初次 + 2 次重试


@pytest.mark.asyncio
async def test_permanent_error_fails_immediately():
    calls = 0

    async def perm():
        nonlocal calls
        calls += 1
        raise ValueError("401 Unauthorized")

    with pytest.raises(ValueError):
        await with_retry(perm, max_retries=5, base_delay=0.01, jitter=False)
    assert calls == 1  # 不重试


@pytest.mark.asyncio
async def test_retries_exhausted_raise_last():
    async def always():
        raise ConnectionError("connection reset")

    with pytest.raises(ConnectionError):
        await with_retry(always, max_retries=2, base_delay=0.01, jitter=False)


@pytest.mark.asyncio
async def test_sync_fn_returning_plain_value():
    """fn 可返回普通值（非协程）。"""
    result = await with_retry(lambda: "plain", max_retries=0)
    assert result == "plain"


@pytest.mark.asyncio
async def test_on_retry_callback_fired():
    attempts = []

    async def flaky():
        raise TimeoutError("timeout")

    def cb(n, exc):
        attempts.append(n)

    with pytest.raises(TimeoutError):
        await with_retry(flaky, max_retries=2, base_delay=0.01, jitter=False, on_retry=cb)
    assert attempts == [1, 2]


def test_classification():
    assert is_retryable_error("request timed out")
    assert is_retryable_error("429 Too Many Requests")
    assert is_retryable_error("Connection reset by peer")
    assert is_retryable_error("503 Service Unavailable")
    assert not is_retryable_error("401 Unauthorized")
    assert not is_retryable_error("403 Forbidden")
    assert not is_retryable_error("路径越界")
    assert not is_retryable_error("FileNotFoundError: 不存在.txt")
