"""配置回显掩码单测：api_key 只显前缀，绝不含尾部字符。"""
from __future__ import annotations

from app.api.v1.config import _mask


def test_mask_prefix_only() -> None:
    m = _mask("sk-1234567890abcdef")
    assert m.startswith("sk-123")
    assert "abcdef" not in m
    assert m.endswith("…")


def test_mask_edge_cases() -> None:
    assert _mask("short") == "…"
    assert _mask("") == ""
    assert _mask(None) == ""
