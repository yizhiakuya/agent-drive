"""Lease-based task runner used both in-process and by the worker service."""
from __future__ import annotations

import asyncio
import logging
import os
import random
import socket
import time
import uuid

from ..core.retry import is_retryable_error
from .registry import JobCancelled, JobContext, JobRegistry, PermanentJobError, RetryableJobError
from .store import JobStore

logger = logging.getLogger("agent-drive.tasks")


class JobRunner:
    def __init__(
        self,
        store: JobStore,
        registry: JobRegistry,
        *,
        poll_seconds: float = 1.0,
        lease_seconds: float = 60.0,
        worker_id: str | None = None,
    ) -> None:
        self.store = store
        self.registry = registry
        self.poll_seconds = max(0.05, poll_seconds)
        self.lease_seconds = max(5.0, lease_seconds)
        self.worker_id = worker_id or f"{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}"
        self.started_at = time.time()
        self._stop = asyncio.Event()
        self._tasks: list[asyncio.Task] = []

    async def start(self) -> None:
        if self._tasks:
            return
        self._stop.clear()
        lanes = self.registry.lanes()
        self.store.heartbeat_worker(self.worker_id, lanes, started_at=self.started_at)
        for lane, concurrency in lanes.items():
            for index in range(concurrency):
                self._tasks.append(asyncio.create_task(
                    self._lane_loop(lane), name=f"job-{lane}-{index}",
                ))
        self._tasks.append(asyncio.create_task(self._schedule_loop(), name="job-schedules"))
        self._tasks.append(asyncio.create_task(self._worker_heartbeat_loop(), name="job-worker-heartbeat"))
        logger.info("task worker started: %s lanes=%s", self.worker_id, lanes)

    async def stop(self) -> None:
        if not self._tasks:
            return
        self._stop.set()
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()
        self.store.remove_worker(self.worker_id)
        logger.info("task worker stopped: %s", self.worker_id)

    async def run_forever(self) -> None:
        await self.start()
        await self._stop.wait()

    async def run_one(self, lane: str | None = None) -> bool:
        lanes = [lane] if lane else list(self.registry.lanes())
        for candidate in lanes:
            job = self.store.claim(candidate, self.worker_id, self.lease_seconds)
            if job is not None:
                await self._execute(job)
                return True
        return False

    async def _wait(self, seconds: float) -> None:
        try:
            await asyncio.wait_for(self._stop.wait(), timeout=seconds)
        except TimeoutError:
            pass

    async def _lane_loop(self, lane: str) -> None:
        while not self._stop.is_set():
            try:
                worked = await self.run_one(lane)
                if not worked:
                    await self._wait(self.poll_seconds)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("task lane loop failed: %s", lane)
                await self._wait(self.poll_seconds)

    async def _schedule_loop(self) -> None:
        while not self._stop.is_set():
            try:
                created = self.store.dispatch_due_schedules()
                if created:
                    logger.info("dispatched %s scheduled tasks", len(created))
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("schedule dispatch failed")
            await self._wait(min(15.0, max(1.0, self.poll_seconds * 5)))

    async def _worker_heartbeat_loop(self) -> None:
        lanes = self.registry.lanes()
        while not self._stop.is_set():
            self.store.heartbeat_worker(self.worker_id, lanes, started_at=self.started_at)
            await self._wait(10.0)

    async def _job_heartbeat(self, context: JobContext) -> None:
        interval = max(1.0, min(10.0, self.lease_seconds / 3))
        while True:
            await asyncio.sleep(interval)
            cancelled = self.store.heartbeat(context.job.id, self.worker_id, self.lease_seconds)
            if cancelled is None:
                context.cancel_event.set()
                return
            if cancelled:
                context.cancel_event.set()

    async def _execute(self, job) -> None:
        spec = self.registry.get(job.type)
        if spec is None:
            self.store.fail(job.id, self.worker_id, f"未知任务类型：{job.type}", retryable=False, retry_delay=0)
            return
        context = JobContext(job, self.store)
        heartbeat = asyncio.create_task(self._job_heartbeat(context), name=f"heartbeat-{job.id[:8]}")
        try:
            call = spec.handler(context, job.payload)
            result = await asyncio.wait_for(call, spec.timeout_seconds) if spec.timeout_seconds else await call
            context.check_cancelled()
            self.store.complete(job.id, self.worker_id, result)
        except asyncio.CancelledError:
            self.store.release(job.id, self.worker_id)
            raise
        except JobCancelled as exc:
            self.store.fail(job.id, self.worker_id, str(exc), retryable=False, retry_delay=0)
        except PermanentJobError as exc:
            self.store.fail(job.id, self.worker_id, str(exc), retryable=False, retry_delay=0)
        except RetryableJobError as exc:
            self.store.fail(job.id, self.worker_id, str(exc), retryable=True, retry_delay=self._retry_delay(job.attempts))
        except Exception as exc:
            text = str(exc) or exc.__class__.__name__
            self.store.fail(
                job.id, self.worker_id, text, retryable=is_retryable_error(text),
                retry_delay=self._retry_delay(job.attempts),
            )
            logger.exception("task failed: %s %s", job.type, job.id)
        finally:
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)

    @staticmethod
    def _retry_delay(attempts: int) -> float:
        base = min(300.0, 2 ** max(0, attempts - 1) * 5.0)
        return base + random.uniform(0, base * 0.2)
