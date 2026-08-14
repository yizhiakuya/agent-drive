"""Durable task queue and indexing lifecycle tests."""
from __future__ import annotations

import asyncio
import time
from pathlib import Path
from types import SimpleNamespace

from app.core.config import Settings
from app.core.container import Container
from app.tasks.handlers import _rebuild_index
from app.tasks.models import CANCELLED, FAILED, QUEUED, RETRY_WAIT, SUCCEEDED
from app.tasks.registry import HandlerSpec, JobRegistry
from app.tasks.runner import JobRunner
from app.tasks.store import JobStore


def test_enqueue_dedupe_complete_and_requeue(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    first, created = store.enqueue("test", {"n": 1}, lane="io", dedupe_key="same")
    duplicate, duplicate_created = store.enqueue("test", {"n": 2}, lane="io", dedupe_key="same")
    assert created is True
    assert duplicate_created is False
    assert duplicate.id == first.id

    claimed = store.claim("io", "worker", 30)
    assert claimed is not None and claimed.attempts == 1
    assert store.update_progress(claimed.id, 1, 2, "half") is True
    progress_cursor = store.latest_event_id()
    assert store.update_progress(claimed.id, 1, 2, "half") is False
    assert store.latest_event_id() == progress_cursor
    assert store.complete(claimed.id, "worker", {"ok": True}) is True
    done = store.get(claimed.id)
    assert done is not None and done.status == SUCCEEDED
    assert done.progress_current == 1

    next_job, next_created = store.enqueue("test", {}, lane="io", dedupe_key="same")
    assert next_created is True and next_job.id != first.id


def test_retry_cancel_and_lease_recovery(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    retry_job, _ = store.enqueue("test", {}, lane="io", max_attempts=2)
    claimed = store.claim("io", "worker", 30)
    assert claimed and claimed.id == retry_job.id
    state = store.fail(claimed.id, "worker", "timeout", retryable=True, retry_delay=0)
    assert state == RETRY_WAIT
    claimed_again = store.claim("io", "worker", 30)
    assert claimed_again and claimed_again.attempts == 2
    assert claimed_again.error is None
    state = store.fail(claimed_again.id, "worker", "bad input", retryable=False, retry_delay=0)
    assert state == FAILED
    assert store.retry(retry_job.id).status == QUEUED

    queued, _ = store.enqueue("cancel", {}, lane="io")
    assert store.cancel(queued.id).status == CANCELLED

    leased, _ = store.enqueue("leased", {}, lane="other")
    claimed_lease = store.claim("other", "dead-worker", 1)
    assert claimed_lease and claimed_lease.id == leased.id
    assert store.recover_expired(now=time.time() + 2) == 1
    assert store.get(leased.id).status == QUEUED

    exhausted, _ = store.enqueue("exhausted", {}, lane="once", max_attempts=1)
    claimed_exhausted = store.claim("once", "dead-worker", 1)
    assert claimed_exhausted and claimed_exhausted.id == exhausted.id
    assert store.recover_expired(now=time.time() + 2) == 1
    failed = store.get(exhausted.id)
    assert failed is not None and failed.status == FAILED
    assert "租约" in (failed.error or "")

    cancelling, _ = store.enqueue("cancelling", {}, lane="cancel-running")
    claimed_cancelling = store.claim("cancel-running", "worker", 30)
    assert claimed_cancelling and claimed_cancelling.id == cancelling.id
    assert store.cancel(cancelling.id).status == "cancelling"
    assert store.fail(cancelling.id, "worker", "任务已取消", retryable=False, retry_delay=0) == CANCELLED
    assert store.get(cancelling.id).error is None


def test_release_finishes_cancelled_job_instead_of_stranding_it(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    job, _ = store.enqueue("cancel-on-stop", {}, lane="io", dedupe_key="same-resource")
    claimed = store.claim("io", "worker", 30)
    assert claimed and claimed.id == job.id
    assert store.cancel(job.id).status == "cancelling"

    assert store.release(job.id, "worker") is True
    released = store.get(job.id)
    assert released is not None and released.status == CANCELLED
    assert released.finished_at is not None

    next_job, created = store.enqueue("cancel-on-stop", {}, lane="io", dedupe_key="same-resource")
    assert created is True and next_job.status == QUEUED


def test_schedule_dispatch_is_persistent_and_deduped(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    store.upsert_schedule(
        "fast",
        "fast test",
        "scheduled.test",
        {"value": 1},
        lane="io",
        schedule_kind="interval",
        schedule_value="60",
        timezone="UTC",
    )
    created = store.dispatch_due_schedules(now=time.time() + 61)
    assert len(created) == 1
    assert created[0].origin == "schedule"
    assert created[0].payload == {"value": 1}
    assert store.dispatch_due_schedules(now=time.time() + 61) == []


def test_prune_history_keeps_recent_and_active_jobs(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    completed_ids = []
    for value in range(3):
        job, _ = store.enqueue("history", {"value": value}, lane="io")
        claimed = store.claim("io", "worker", 30)
        assert claimed and store.complete(claimed.id, "worker", {"value": value})
        completed_ids.append(job.id)
    active, _ = store.enqueue("active", {}, lane="io")
    old = time.time() - 40 * 86400
    with store._connect() as conn:
        conn.execute(
            "UPDATE jobs SET finished_at=?,updated_at=? WHERE id IN (?,?)",
            (old, old, completed_ids[0], completed_ids[1]),
        )

    result = store.prune_history(older_than_days=30, keep_recent=1)
    assert result["jobs"] == 2
    assert store.get(completed_ids[0]) is None
    assert store.get(completed_ids[1]) is None
    assert store.get(completed_ids[2]) is not None
    assert store.get(active.id) is not None


def test_prune_history_keeps_parent_while_a_child_is_retained(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    parent, _ = store.enqueue("parent", {}, lane="parent")
    child, _ = store.enqueue("child", {}, lane="child", parent_id=parent.id)
    old = time.time() - 40 * 86400
    with store._connect() as conn:
        conn.execute(
            "UPDATE jobs SET status=?,finished_at=?,updated_at=? WHERE id=?",
            (SUCCEEDED, old, old, parent.id),
        )
        conn.execute(
            "UPDATE jobs SET status=?,finished_at=?,updated_at=? WHERE id=?",
            (SUCCEEDED, time.time(), time.time(), child.id),
        )

    result = store.prune_history(older_than_days=30, keep_recent=1)
    assert result["jobs"] == 0
    assert store.get(parent.id) is not None
    assert store.get(child.id).parent_id == parent.id


def test_overview_counts_roots_and_caches_index_stats(tmp_path: Path, monkeypatch):
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    parent, _ = container.job_store.enqueue("parent", {}, lane="parent")
    container.job_store.enqueue("child", {}, lane="child", parent_id=parent.id)
    calls = 0

    def vector_stats():
        nonlocal calls
        calls += 1
        return {"eligible_files": calls}

    monkeypatch.setattr(container.ingest, "vector_stats", vector_stats)
    first = container.tasks.overview()
    second = container.tasks.overview()
    assert first["counts"] == {QUEUED: 1}
    assert second["index"] == {"eligible_files": 1}
    assert calls == 1

    container.tasks.invalidate_index_stats()
    assert container.tasks.overview()["index"] == {"eligible_files": 2}
    assert calls == 2


def test_runner_executes_registered_handler(tmp_path: Path):
    store = JobStore(tmp_path / "tasks.sqlite3")
    registry = JobRegistry()

    async def handler(context, payload):
        context.progress(1, 1, "done")
        return {"echo": payload["value"]}

    registry.register(HandlerSpec("echo", handler, "io", timeout_seconds=5))
    job, _ = store.enqueue("echo", {"value": "ok"}, lane="io")
    runner = JobRunner(store, registry, worker_id="test-worker", lease_seconds=10)
    assert asyncio.run(runner.run_one("io")) is True
    finished = store.get(job.id)
    assert finished is not None and finished.status == SUCCEEDED
    assert finished.result == {"echo": "ok"}


class FakeEmbedder:
    base_url = "https://embedding.invalid/v1"
    model = "fake-v1"

    async def embed(self, texts, task="text-matching"):
        assert task in {"retrieval.query", "retrieval.passage", "text-matching"}
        return [[float(len(text)), 1.0] for text in texts]


def test_storage_change_enqueues_and_refreshes_index(tmp_path: Path):
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    embedder = FakeEmbedder()
    container.llm.get_embedding_provider = lambda: embedder
    container.tasks.refresh_embedder()

    container.storage.save_bytes("docs/note.txt", b"first version")
    queued = container.job_store.list_jobs(task_type="index.file")
    assert len(queued) == 1 and queued[0].status == QUEUED
    assert asyncio.run(container.task_runner.run_one("index")) is True
    assert container.ingest.is_vector_current("docs/note.txt") is True

    old_revision = container.ingest.source_revision("docs/note.txt")
    container.storage.save_bytes("docs/note.txt", b"second and longer version")
    assert container.ingest.is_vector_current("docs/note.txt") is False
    newest = container.job_store.list_jobs(task_type="index.file")[0]
    assert newest.payload["expected_revision"] != old_revision
    assert asyncio.run(container.task_runner.run_one("index")) is True
    assert container.ingest.is_vector_current("docs/note.txt") is True

    container.storage.move_to_trash("docs/note.txt")
    assert container.ingest.is_vector_current("docs/note.txt") is False


def test_force_rebuild_keeps_current_index_until_replacement(tmp_path: Path, monkeypatch):
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    embedder = FakeEmbedder()
    container.llm.get_embedding_provider = lambda: embedder
    container.tasks.refresh_embedder()
    container.storage.save_bytes("docs/current.txt", b"current content")
    assert asyncio.run(container.task_runner.run_one("index")) is True
    assert container.ingest.is_vector_current("docs/current.txt") is True

    queued: list[bool] = []

    def capture_enqueue(path, *, force=False, **kwargs):
        queued.append(force)
        return None, False

    monkeypatch.setattr(container.tasks, "enqueue_index", capture_enqueue)
    context = SimpleNamespace(
        job=SimpleNamespace(id="parent", attempts=1),
        progress=lambda *args: None,
        check_cancelled=lambda: None,
    )
    asyncio.run(_rebuild_index(container, context, {"force": True, "prefix": "docs"}))

    assert queued == [True]
    assert container.ingest.is_vector_current("docs/current.txt") is True


def test_rebuild_parent_tracks_child_jobs(tmp_path: Path):
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
        task_poll_seconds=0.02,
        task_lease_seconds=10,
    )
    container = Container(settings)
    embedder = FakeEmbedder()
    container.llm.get_embedding_provider = lambda: embedder
    container.tasks.refresh_embedder()
    container.storage.save_bytes("docs/a.txt", b"alpha")
    container.storage.save_bytes("docs/b.txt", b"beta")
    parent, _ = container.tasks.enqueue_rebuild(prefix="docs", force=True, origin="test")

    async def run():
        await container.task_runner.start()
        try:
            deadline = asyncio.get_running_loop().time() + 5
            while asyncio.get_running_loop().time() < deadline:
                latest = container.job_store.get(parent.id)
                if latest and latest.status in {SUCCEEDED, FAILED, CANCELLED}:
                    return latest
                await asyncio.sleep(0.02)
            raise AssertionError("rebuild task timed out")
        finally:
            await container.task_runner.stop()

    finished = asyncio.run(run())
    assert finished.status == SUCCEEDED, finished.error
    assert finished.progress_current == finished.progress_total == 2
    assert container.ingest.vector_stats()["vector_files"] == 2
