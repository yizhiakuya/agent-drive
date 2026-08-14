"""Built-in task handlers."""
from __future__ import annotations

import asyncio
from typing import Any

from .models import CANCELLED, FAILED, SUCCEEDED
from .registry import HandlerSpec, JobContext, JobRegistry, PermanentJobError


async def _index_file(container, context: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
    path = str(payload.get("path") or "")
    if not path:
        raise PermanentJobError("任务缺少文件路径")
    source = container.storage.resolve(path)
    if not source.is_file():
        container.ingest.invalidate(path, recursive=True)
        return {"path": path, "skipped": "source_missing"}

    expected = str(payload.get("expected_revision") or "")
    current_revision = container.ingest.source_revision(path)
    if expected and current_revision != expected:
        container.tasks.enqueue_index(path, origin="index.reconcile", priority=30)
        return {"path": path, "skipped": "source_changed"}

    context.progress(0, 2, "正在提取内容")
    meta = await asyncio.to_thread(container.ingest.extract, path)
    context.check_cancelled()
    if container.ingest.source_revision(path) != current_revision:
        container.ingest.invalidate(path)
        container.tasks.enqueue_index(path, origin="index.reconcile", priority=30)
        return {"path": path, "skipped": "source_changed"}

    context.progress(1, 2, "正在生成向量")
    embedder = container.tasks.refresh_embedder()
    if meta.get("chars", 0) <= 0:
        container.ingest.invalidate_vector(path)
        context.progress(2, 2, "没有可索引文本")
        return {"path": path, "extracted": True, "vectorized": False, "reason": meta.get("method")}
    if embedder is None:
        container.ingest.invalidate_vector(path)
        context.progress(2, 2, "文本已提取，未配置向量服务")
        return {"path": path, "extracted": True, "vectorized": False, "reason": "embedding_not_configured"}

    result = await container.ingest.embed_file(path, expected_revision=current_revision)
    context.check_cancelled()
    if not result.get("ok"):
        if result.get("stale"):
            container.tasks.enqueue_index(path, origin="index.reconcile", priority=30)
            return {"path": path, "skipped": "source_changed"}
        raise PermanentJobError(str(result.get("error") or "向量生成失败"))
    context.progress(2, 2, "索引完成")
    return {"path": path, "extracted": True, "vectorized": True, **result}


async def _rebuild_index(container, context: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
    prefix = str(payload.get("prefix") or "")
    force = bool(payload.get("force"))
    paths = container.ingest.iter_indexable_files(prefix)
    total = len(paths)
    context.progress(0, total, "正在扫描文件")
    tracked: set[str] = set()
    already_current = 0
    for path in paths:
        context.check_cancelled()
        job, _ = container.tasks.enqueue_index(
            path,
            force=force,
            origin="index.rebuild",
            parent_id=context.job.id,
            priority=10,
        )
        if job is None:
            already_current += 1
        else:
            tracked.add(job.id)
    context.progress(already_current, total, f"已安排 {len(tracked)} 个文件")

    while tracked:
        context.check_cancelled()
        statuses = container.job_store.get_statuses(tracked)
        terminal = {job_id for job_id, status in statuses.items() if status in {SUCCEEDED, FAILED, CANCELLED}}
        done = already_current + len(terminal)
        context.progress(done, total, f"已处理 {done}/{total}")
        if len(terminal) == len(tracked):
            failures = sum(statuses.get(job_id) in {FAILED, CANCELLED} for job_id in tracked)
            if failures:
                raise PermanentJobError(f"{failures} 个文件索引任务失败")
            break
        await asyncio.sleep(0.5)

    return {"files": total, "indexed": len(tracked), "already_current": already_current, "prefix": prefix}


async def _cleanup_index(container, context: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
    context.progress(0, 1, "正在清理失效索引")
    result = await asyncio.to_thread(container.ingest.cleanup_orphans)
    context.progress(1, 1, "清理完成")
    return result


async def _daily_maintenance(container, context: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
    context.progress(0, 3, "正在清理失效索引")
    index_result = await asyncio.to_thread(container.ingest.cleanup_orphans)
    context.check_cancelled()
    context.progress(1, 3, "正在清理回收站")
    removed = await asyncio.to_thread(container.storage.cleanup_trash, 30)
    context.check_cancelled()
    context.progress(2, 3, "正在清理任务历史")
    history = await asyncio.to_thread(container.job_store.prune_history)
    context.progress(3, 3, "维护完成")
    return {"index": index_result, "trash_removed": removed, "task_history": history}


async def _run_automation(container, context: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
    context.progress(0, 1, "正在执行自动化规则")
    result = await container.scheduler.execute_once(context=context)
    context.progress(1, 1, "自动化执行完成")
    return result


def build_job_registry(container) -> JobRegistry:
    registry = JobRegistry()
    registry.register(HandlerSpec("index.file", lambda c, p: _index_file(container, c, p), "index", 1, 4, 600))
    registry.register(HandlerSpec(
        "index.rebuild", lambda c, p: _rebuild_index(container, c, p), "orchestration", 1, 2, 86400,
    ))
    registry.register(HandlerSpec(
        "index.cleanup", lambda c, p: _cleanup_index(container, c, p), "maintenance", 1, 2, 600,
    ))
    registry.register(HandlerSpec(
        "maintenance.daily", lambda c, p: _daily_maintenance(container, c, p), "maintenance", 1, 2, 900,
    ))
    registry.register(HandlerSpec(
        "automation.run", lambda c, p: _run_automation(container, c, p), "automation", 1, 2, 1800,
    ))
    return registry
