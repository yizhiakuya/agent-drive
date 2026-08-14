"""Embedding Provider 抽象（M2b 语义搜索）。

设计：与 LLM 三协议同模式——协议接口 + Jina 云实现 + 可扩展。
M2b 用 Jina AI 云（jina-embeddings-v3, 1024 维, 中文友好, 8K token 输入）。
"""
from __future__ import annotations

import time
from typing import Any, Protocol

import httpx


class EmbeddingProvider(Protocol):
    async def embed(self, texts: list[str], task: str = "text-matching") -> list[list[float]]: ...
    async def test_connection(self) -> dict[str, Any]: ...


class JinaEmbeddingProvider:
    """Jina AI Embeddings API（POST /v1/embeddings）"""

    def __init__(self, base_url: str, api_key: str, model: str = "jina-embeddings-v3"):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model

    async def embed(self, texts: list[str], task: str = "text-matching") -> list[list[float]]:
        """文本 → 向量。task 取值: retrieval.query / retrieval.passage / text-matching。"""
        from ..core.retry import with_retry

        async def _call():
            async with httpx.AsyncClient(timeout=60) as client:
                resp = await client.post(
                    f"{self.base_url}/embeddings",
                    headers={"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
                    json={"model": self.model, "input": texts, "task": task},
                )
                resp.raise_for_status()
                data = resp.json()
                return [d["embedding"] for d in data["data"]]

        return await with_retry(_call)

    async def test_connection(self) -> dict[str, Any]:
        t0 = time.time()
        try:
            vecs = await self.embed(["连接测试"])
            return {
                "ok": True,
                "model": self.model,
                "dimensions": len(vecs[0]),
                "latency_ms": int((time.time() - t0) * 1000),
            }
        except Exception as e:
            return {"ok": False, "error": str(e), "latency_ms": int((time.time() - t0) * 1000)}


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """余弦相似度（纯 Python，无 numpy 依赖也可用；有 numpy 则更快）。"""
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)
