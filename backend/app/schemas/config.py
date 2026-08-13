"""配置相关数据模型"""
from __future__ import annotations

from pydantic import BaseModel


class LLMConfigIn(BaseModel):
    type: str
    base_url: str
    api_key: str
    model: str
