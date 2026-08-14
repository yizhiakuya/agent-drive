"""Task handler registry and execution context."""
from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

from .models import JobRecord
from .store import JobStore


class JobCancelled(Exception):
    pass


class RetryableJobError(Exception):
    pass


class PermanentJobError(Exception):
    pass


JobHandler = Callable[["JobContext", dict[str, Any]], Awaitable[dict[str, Any] | None]]


@dataclass(frozen=True, slots=True)
class HandlerSpec:
    task_type: str
    handler: JobHandler
    lane: str
    concurrency: int = 1
    max_attempts: int = 3
    timeout_seconds: float | None = None


class JobRegistry:
    def __init__(self) -> None:
        self._handlers: dict[str, HandlerSpec] = {}

    def register(self, spec: HandlerSpec) -> None:
        if spec.task_type in self._handlers:
            raise ValueError(f"duplicate task handler: {spec.task_type}")
        self._handlers[spec.task_type] = spec

    def get(self, task_type: str) -> HandlerSpec | None:
        return self._handlers.get(task_type)

    def lanes(self) -> dict[str, int]:
        lanes: dict[str, int] = {}
        for spec in self._handlers.values():
            lanes[spec.lane] = max(lanes.get(spec.lane, 0), max(1, spec.concurrency))
        return lanes


class JobContext:
    def __init__(self, job: JobRecord, store: JobStore):
        self.job = job
        self.store = store
        self.cancel_event = asyncio.Event()

    def progress(self, current: int, total: int, message: str = "") -> None:
        self.store.update_progress(self.job.id, current, total, message)

    def is_cancelled(self) -> bool:
        if self.cancel_event.is_set():
            return True
        latest = self.store.get(self.job.id)
        return latest is None or latest.cancel_requested

    def check_cancelled(self) -> None:
        if self.is_cancelled():
            raise JobCancelled("任务已取消")
