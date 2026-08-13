"""重试与退避策略（可靠性维度：鲁棒性）。

- LLM 调用：瞬态错误（超时/限流/网络）指数退避重试
- 工具执行：瞬态错误重试，永久错误立即失败
- 抖动：避免重试风暴
"""
from __future__ import annotations

import asyncio
import random
import time
from typing import Any, Awaitable, Callable

# 瞬态错误特征（可重试）：超时/限流/网络/服务不可用
RETRYABLE_PATTERNS = (
    "timeout", "timed out", "timedout",
    "connection", "connrefused", "network",
    "429", "rate limit", "too many requests",
    "500", "502", "503", "504",
    "service unavailable", "overloaded", "temporarily",
    "connection reset", "econnreset", "eof",
)

# 永久错误特征（不重试）：认证/参数/资源不存在
PERMANENT_PATTERNS = (
    "401", "403", "unauthorized", "forbidden",
    "invalid", "not found", "does not exist", "不存在",
    "越界", "permission",
)


def is_retryable_error(error_text: str) -> bool:
    """判断错误是否瞬态（可重试）。"""
    t = (error_text or "").lower()
    if any(p in t for p in PERMANENT_PATTERNS):
        return False
    return any(p in t for p in RETRYABLE_PATTERNS)


async def with_retry(
    fn: Callable[[], Awaitable[Any]],
    *,
    max_retries: int = 2,
    base_delay: float = 0.5,
    max_delay: float = 8.0,
    jitter: bool = True,
    retryable: Callable[[str], bool] = is_retryable_error,
    on_retry: Callable[[int, Exception], None] | None = None,
) -> Any:
    """执行 fn，瞬态失败时指数退避重试。

    退避序列: base_delay * 2^attempt（上限 max_delay）+ 可选抖动。
    """
    last_exc: Exception | None = None
    for attempt in range(max_retries + 1):
        try:
            return await fn()
        except Exception as e:
            last_exc = e
            text = str(e)
            if attempt >= max_retries or not retryable(text):
                raise
            delay = min(base_delay * (2 ** attempt), max_delay)
            if jitter:
                delay += random.uniform(0, delay * 0.3)
            if on_retry:
                on_retry(attempt + 1, e)
            await asyncio.sleep(delay)
    raise last_exc  # pragma: no cover
