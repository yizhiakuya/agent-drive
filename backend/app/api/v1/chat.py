"""v1 对话路由"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ...core.errors import AppError, ConfigError
from ...schemas.chat import ChatRequest, ChatResponse
from ..deps import get_container

router = APIRouter(prefix="/chat", tags=["chat"])


@router.post("", response_model=ChatResponse)
async def chat(req: ChatRequest, container=Depends(get_container)):
    try:
        loop = container.build_agent()
    except ConfigError as e:
        raise HTTPException(400, e.message)
    result = await loop.run(req.message, req.history, req.confirmations, req.session_id)
    return ChatResponse(**result)
