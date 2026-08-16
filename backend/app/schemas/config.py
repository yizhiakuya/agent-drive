"""配置相关数据模型"""
from __future__ import annotations

from pydantic import BaseModel


class LLMConfigIn(BaseModel):
    type: str
    base_url: str
    api_key: str
    model: str


class LLMModelsIn(BaseModel):
    """获取模型列表请求：只读探测，model 不必填。"""
    type: str
    base_url: str
    api_key: str = ""
