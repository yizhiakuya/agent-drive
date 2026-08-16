"""v1 对话路由"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from ...core.errors import ConfigError
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


@router.post("/stream")
async def chat_stream(req: ChatRequest, container=Depends(get_container)):
    """SSE 流式对话：text 事件逐块推送，tool_trace 事件推送工具调用，done 事件汇总。"""
    import json as _json

    from fastapi.responses import StreamingResponse

    try:
        loop = container.build_agent()
    except ConfigError as e:
        raise HTTPException(400, e.message)

    async def event_stream():
        try:
            async for event, payload in loop.run_stream(
                req.message, req.history, req.confirmations, req.session_id
            ):
                # 线上契约：SSE 每个 data 必须是 JSON 对象（前端解析器拒绝裸字符串）
                if event == "text" and isinstance(payload, str):
                    payload = {"text": payload}
                data = _json.dumps(payload, ensure_ascii=False)
                yield f"event: {event}\ndata: {data}\n\n"
        except Exception as e:
            yield f"event: error\ndata: {_json.dumps({'error': str(e)}, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
