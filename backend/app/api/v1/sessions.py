"""v1 会话路由"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ..deps import get_container

router = APIRouter(prefix="/sessions", tags=["sessions"])


@router.get("")
async def list_sessions(container=Depends(get_container)):
    return {"sessions": container.sessions.list()}


@router.get("/{sid}")
async def get_session(sid: str, container=Depends(get_container)):
    meta = container.sessions.get(sid)
    if meta is None:
        raise HTTPException(404, "会话不存在")
    return {"meta": meta, "messages": container.sessions.messages(sid)}


@router.delete("/{sid}")
async def delete_session(sid: str, container=Depends(get_container)):
    if not container.sessions.delete(sid):
        raise HTTPException(404, "会话不存在")
    return {"deleted": sid}


@router.post("/{sid}/summarize")
async def summarize_session(sid: str, container=Depends(get_container)):
    loop = container.build_agent()
    return await loop.summarize_session(sid)
