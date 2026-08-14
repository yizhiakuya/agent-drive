"""重试与退避测试：瞬态错误重试、永久错误不重试、指数退避。"""
import asyncio
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
try:  # Windows 控制台 GBK：强制 UTF-8 输出，避免 ✅/中文打印崩溃
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from app.core.retry import is_retryable_error, with_retry


async def test_retry_transient_success():
    """瞬态错误（超时）失败 2 次后成功 → 共调用 3 次"""
    calls = []

    async def flaky():
        calls.append(1)
        if len(calls) < 3:
            raise TimeoutError("request timed out")
        return "ok"

    result = await with_retry(flaky, max_retries=2, base_delay=0.05, jitter=False)
    assert result == "ok"
    assert len(calls) == 3
    print("✅ 瞬态错误重试后成功（调用 3 次）")


async def test_retry_permanent_no_retry():
    """永久错误（401 认证）不重试，立即抛"""
    calls = []

    async def perm():
        calls.append(1)
        raise ValueError("401 Unauthorized")

    try:
        await with_retry(perm, max_retries=2, base_delay=0.05, jitter=False)
        assert False, "应抛异常"
    except ValueError:
        pass
    assert len(calls) == 1
    print("✅ 永久错误不重试（仅调用 1 次）")


async def test_retry_exhausted():
    """重试耗尽 → 抛最后一次异常"""
    async def always():
        raise ConnectionError("connection reset")

    try:
        await with_retry(always, max_retries=2, base_delay=0.05, jitter=False)
        assert False, "应抛异常"
    except ConnectionError:
        pass
    print("✅ 重试耗尽后抛出异常")


async def test_exponential_backoff_delay():
    """指数退避：2 次重试的总等待 ≈ 0.1 + 0.2（base=0.1）"""
    t0 = time.time()

    async def flaky():
        raise TimeoutError("timeout")

    try:
        await with_retry(flaky, max_retries=2, base_delay=0.1, jitter=False)
    except TimeoutError:
        pass
    elapsed = time.time() - t0
    expected = 0.1 + 0.2  # base * 2^0 + base * 2^1
    assert elapsed >= expected * 0.8, f"退避延迟不足: {elapsed}s"
    assert elapsed < expected * 2.5, f"退避延迟过大: {elapsed}s"
    print(f"✅ 指数退避（实际等待 {elapsed:.2f}s ≈ 预期 {expected:.2f}s）")


async def test_retryable_classification():
    """错误分类：瞬态 vs 永久"""
    assert is_retryable_error("request timed out")
    assert is_retryable_error("429 Too Many Requests")
    assert is_retryable_error("Connection reset by peer")
    assert is_retryable_error("503 Service Unavailable")
    assert not is_retryable_error("401 Unauthorized")
    assert not is_retryable_error("路径越界")
    assert not is_retryable_error("FileNotFoundError: 不存在.txt")
    print("✅ 错误分类正确（6 类瞬态 / 3 类永久）")


async def main():
    await test_retry_transient_success()
    await test_retry_permanent_no_retry()
    await test_retry_exhausted()
    await test_exponential_backoff_delay()
    await test_retryable_classification()
    print("\n🎉 重试与退避测试全部通过！")


if __name__ == "__main__":
    asyncio.run(main())
