"""Authenticated background task API."""
from __future__ import annotations

import asyncio
import json
import time

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from ...tasks.models import ALL_STATUSES, CANCELLED, FAILED
from ..deps import get_container

router = APIRouter(prefix="/tasks", tags=["tasks"])


class RebuildRequest(BaseModel):
    prefix: str = ""
    force: bool = False


def _get_job(container, task_id: str):
    job = container.job_store.get(task_id)
    if job is None:
        raise HTTPException(404, "任务不存在")
    return job


@router.get("")
async def list_tasks(
    container=Depends(get_container),
    status: str = "",
    task_type: str = "",
    include_children: bool = False,
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    statuses = {item for item in status.split(",") if item} or None
    if statuses and not statuses.issubset(ALL_STATUSES):
        raise HTTPException(400, "无效任务状态")
    jobs = container.job_store.list_jobs(
        statuses=statuses,
        task_type=task_type or None,
        include_children=include_children,
        limit=limit,
        offset=offset,
    )
    return {"items": [job.to_dict() for job in jobs], "overview": container.tasks.overview()}


@router.get("/summary")
async def task_summary(container=Depends(get_container)):
    return container.tasks.overview()


@router.post("/rebuild-index")
async def rebuild_index(body: RebuildRequest, container=Depends(get_container)):
    if container.tasks.refresh_embedder() is None:
        raise HTTPException(409, "请先配置可用的 embedding 服务")
    try:
        job, created = container.tasks.enqueue_rebuild(
            prefix=body.prefix,
            force=body.force,
            origin="api",
        )
    except (FileNotFoundError, PermissionError, ValueError) as exc:
        raise HTTPException(400, str(exc)) from exc
    return {"queued": created, "task": job.to_dict()}


@router.post("/cleanup-index")
async def cleanup_index(container=Depends(get_container)):
    job, created = container.tasks.enqueue_cleanup(origin="api")
    return {"queued": created, "task": job.to_dict()}


@router.get("/events")
async def task_events(
    request: Request,
    container=Depends(get_container),
    after: int | None = Query(None, ge=0),
):
    header_cursor = request.headers.get("last-event-id", "")
    has_cursor = after is not None or header_cursor.isdigit()
    cursor = max(after or 0, int(header_cursor) if header_cursor.isdigit() else 0)
    if not has_cursor:
        cursor = container.job_store.latest_event_id()

    async def stream():
        nonlocal cursor
        deadline = time.monotonic() + 55
        while time.monotonic() < deadline:
            if await request.is_disconnected():
                return
            events = container.job_store.list_events(cursor, limit=100)
            if events:
                for event in events:
                    cursor = event["id"]
                    yield f"id: {cursor}\nevent: task\ndata: {json.dumps(event, ensure_ascii=False)}\n\n"
            else:
                yield ": keepalive\n\n"
            await asyncio.sleep(1)

    return StreamingResponse(
        stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/{task_id}")
async def task_detail(task_id: str, container=Depends(get_container)):
    job = _get_job(container, task_id)
    return {"task": job.to_dict(), "children": container.job_store.child_summary(task_id)}


@router.post("/{task_id}/cancel")
async def cancel_task(task_id: str, container=Depends(get_container)):
    _get_job(container, task_id)
    job = container.job_store.cancel(task_id)
    if job is None:
        raise HTTPException(409, "任务无法取消")
    if job.type == "index.rebuild":
        container.job_store.cancel_children(job.id)
    return {"task": job.to_dict()}


@router.post("/{task_id}/retry")
async def retry_task(task_id: str, container=Depends(get_container)):
    job = _get_job(container, task_id)
    if job.status not in {FAILED, CANCELLED}:
        raise HTTPException(409, "只有失败或已取消的任务可以重试")
    retried = container.job_store.retry(task_id)
    if retried is None:
        raise HTTPException(409, "已有同类任务在运行，暂时无法重试")
    return {"task": retried.to_dict()}
