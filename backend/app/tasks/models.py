"""Task records and state constants."""
from __future__ import annotations

import json
from dataclasses import dataclass
from sqlite3 import Row
from typing import Any

QUEUED = "queued"
RUNNING = "running"
RETRY_WAIT = "retry_wait"
CANCELLING = "cancelling"
SUCCEEDED = "succeeded"
FAILED = "failed"
CANCELLED = "cancelled"

ACTIVE_STATUSES = frozenset({QUEUED, RUNNING, RETRY_WAIT, CANCELLING})
TERMINAL_STATUSES = frozenset({SUCCEEDED, FAILED, CANCELLED})
ALL_STATUSES = ACTIVE_STATUSES | TERMINAL_STATUSES


def _json_load(value: str | None, fallback: Any) -> Any:
    if not value:
        return fallback
    try:
        return json.loads(value)
    except (TypeError, ValueError):
        return fallback


@dataclass(slots=True)
class JobRecord:
    id: str
    type: str
    lane: str
    status: str
    payload: dict[str, Any]
    result: dict[str, Any] | None
    error: str | None
    priority: int
    dedupe_key: str | None
    resource_key: str | None
    parent_id: str | None
    origin: str
    attempts: int
    max_attempts: int
    run_after: float
    lease_owner: str | None
    lease_until: float | None
    cancel_requested: bool
    progress_current: int
    progress_total: int
    progress_message: str
    created_at: float
    updated_at: float
    started_at: float | None
    finished_at: float | None

    @classmethod
    def from_row(cls, row: Row) -> JobRecord:
        return cls(
            id=row["id"],
            type=row["type"],
            lane=row["lane"],
            status=row["status"],
            payload=_json_load(row["payload_json"], {}),
            result=_json_load(row["result_json"], None),
            error=row["error"],
            priority=row["priority"],
            dedupe_key=row["dedupe_key"],
            resource_key=row["resource_key"],
            parent_id=row["parent_id"],
            origin=row["origin"],
            attempts=row["attempts"],
            max_attempts=row["max_attempts"],
            run_after=row["run_after"],
            lease_owner=row["lease_owner"],
            lease_until=row["lease_until"],
            cancel_requested=bool(row["cancel_requested"]),
            progress_current=row["progress_current"],
            progress_total=row["progress_total"],
            progress_message=row["progress_message"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            started_at=row["started_at"],
            finished_at=row["finished_at"],
        )

    def to_dict(self, *, include_payload: bool = False) -> dict[str, Any]:
        data = {
            "id": self.id,
            "type": self.type,
            "lane": self.lane,
            "status": self.status,
            "result": self.result,
            "error": self.error,
            "priority": self.priority,
            "resource_key": self.resource_key,
            "parent_id": self.parent_id,
            "origin": self.origin,
            "attempts": self.attempts,
            "max_attempts": self.max_attempts,
            "cancel_requested": self.cancel_requested,
            "progress": {
                "current": self.progress_current,
                "total": self.progress_total,
                "message": self.progress_message,
            },
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
        }
        if include_payload:
            data["payload"] = self.payload
        return data
