"""Domain-facing task enqueue service."""
from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Any

from .models import JobRecord
from .store import JobStore

logger = logging.getLogger("agent_drive.tasks.service")
_INDEX_STATS_TTL_SECONDS = 15.0


class TaskService:
    def __init__(self, store: JobStore, storage, ingest, llm, *, timezone: str = "Asia/Shanghai") -> None:
        self.store = store
        self.storage = storage
        self.ingest = ingest
        self.llm = llm
        self.timezone = timezone
        self._index_stats_cache: tuple[float, dict[str, Any]] | None = None

    def refresh_embedder(self):
        previous_fingerprint = self.ingest.embedding_fingerprint()
        self.ingest.embedder = self.llm.get_embedding_provider()
        if self.ingest.embedding_fingerprint() != previous_fingerprint:
            self.invalidate_index_stats()
        return self.ingest.embedder

    def invalidate_index_stats(self) -> None:
        self._index_stats_cache = None

    def _index_stats(self) -> dict[str, Any]:
        now = time.monotonic()
        if self._index_stats_cache is not None and self._index_stats_cache[0] > now:
            return dict(self._index_stats_cache[1])
        stats = self.ingest.vector_stats()
        self._index_stats_cache = (now + _INDEX_STATS_TTL_SECONDS, dict(stats))
        return stats

    def enqueue_index(
        self,
        path: str,
        *,
        force: bool = False,
        origin: str = "system",
        parent_id: str | None = None,
        priority: int = 20,
    ) -> tuple[JobRecord | None, bool]:
        try:
            source = self.storage.resolve(path)
            if not source.exists():
                return None, False
            rel = source.relative_to(self.storage.root).as_posix()
            if source.is_dir():
                return self.enqueue_rebuild(prefix=rel, force=force, origin=origin, priority=priority)
            if not self.ingest.is_indexable(rel):
                return None, False
            embedder = self.refresh_embedder()
            if not force and self.ingest.is_index_current(rel, require_vector=embedder is not None):
                return None, False
            revision = self.ingest.source_revision(rel)
            fingerprint = self.ingest.embedding_fingerprint() if embedder is not None else "text-only"
            dedupe = f"index.file:{rel}:{revision}:{fingerprint}"
            return self.store.enqueue(
                "index.file",
                {"path": rel, "expected_revision": revision, "force": force},
                lane="index",
                priority=priority,
                dedupe_key=dedupe,
                resource_key=f"file:{rel}",
                parent_id=parent_id,
                origin=origin,
                max_attempts=4,
            )
        except (FileNotFoundError, PermissionError, ValueError):
            return None, False

    def enqueue_rebuild(
        self,
        *,
        prefix: str = "",
        force: bool = False,
        origin: str = "manual",
        priority: int = 5,
    ) -> tuple[JobRecord, bool]:
        embedder = self.refresh_embedder()
        fingerprint = self.ingest.embedding_fingerprint() if embedder is not None else "text-only"
        requested = prefix.strip("/")
        if requested:
            root = self.storage.resolve(requested)
            if not root.exists():
                raise FileNotFoundError(requested)
            normalized = root.relative_to(self.storage.root).as_posix()
        else:
            normalized = ""
        dedupe = f"index.rebuild:{normalized}:{fingerprint}"
        return self.store.enqueue(
            "index.rebuild",
            {"prefix": normalized, "force": force},
            lane="orchestration",
            priority=priority,
            dedupe_key=dedupe,
            resource_key=f"index:{normalized or '*'}",
            origin=origin,
            max_attempts=2,
        )

    def enqueue_cleanup(self, *, origin: str = "manual") -> tuple[JobRecord, bool]:
        return self.store.enqueue(
            "index.cleanup",
            {},
            lane="maintenance",
            priority=1,
            dedupe_key="index.cleanup",
            resource_key="index:*",
            origin=origin,
            max_attempts=2,
        )

    def enqueue_automation(self, *, origin: str = "manual") -> tuple[JobRecord, bool]:
        return self.store.enqueue(
            "automation.run",
            {},
            lane="automation",
            priority=5,
            dedupe_key="automation.run",
            resource_key="automation:rules",
            origin=origin,
            max_attempts=2,
        )

    def ensure_default_schedules(self) -> None:
        self.store.upsert_schedule(
            "automation-daily",
            "每日自动化规则",
            "automation.run",
            {},
            lane="automation",
            schedule_kind="daily",
            schedule_value="03:30",
            timezone=self.timezone,
            priority=5,
            max_attempts=2,
        )
        self.store.upsert_schedule(
            "maintenance-daily",
            "每日索引与回收站维护",
            "maintenance.daily",
            {},
            lane="maintenance",
            schedule_kind="daily",
            schedule_value="04:15",
            timezone=self.timezone,
            priority=1,
            max_attempts=2,
        )

    def handle_storage_change(self, event: str, paths: list[str]) -> None:
        """Cheap invalidation is synchronous; expensive rebuilding is queued."""
        self.invalidate_index_stats()
        try:
            cleaned = [self._normalize(path) for path in paths if path]
            cleaned = [path for path in cleaned if path and not self._internal(path)]
            for path in cleaned:
                self.ingest.invalidate(path, recursive=True)
            targets: list[str] = []
            if event in {"write", "restore"}:
                targets = cleaned
            elif event in {"copy", "move", "rename"} and cleaned:
                targets = [cleaned[-1]]
            for target in targets:
                source = self.storage.resolve(target)
                if not source.exists():
                    continue
                if source.is_dir():
                    self.enqueue_rebuild(prefix=target, origin=f"storage.{event}", priority=15)
                else:
                    self.enqueue_index(target, origin=f"storage.{event}", priority=30)
        except Exception as exc:
            logger.warning("failed to enqueue index change event=%s paths=%s: %s", event, paths, exc)

    def _normalize(self, path: str) -> str:
        resolved = self.storage.resolve(path)
        return resolved.relative_to(self.storage.root).as_posix()

    @staticmethod
    def _internal(path: str) -> bool:
        parts = Path(path).parts
        return bool(parts and (
            parts[0] in {".index", ".trash", ".storage.lock"}
            or parts[0].startswith((".upload.", ".copy.", ".copy-old."))
        ))

    def overview(self) -> dict[str, Any]:
        workers = self.store.active_workers()
        return {
            "counts": self.store.summary(),
            "workers": {"online": bool(workers), "count": len(workers)},
            "index": self._index_stats(),
        }
