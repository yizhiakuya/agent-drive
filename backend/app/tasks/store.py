"""SQLite-backed durable job queue.

Each operation uses a short-lived connection. SQLite WAL therefore supports the
API process and a separate worker process without a broker service.
"""
from __future__ import annotations

import json
import os
import sqlite3
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .models import (
    ACTIVE_STATUSES,
    CANCELLED,
    CANCELLING,
    FAILED,
    QUEUED,
    RETRY_WAIT,
    RUNNING,
    SUCCEEDED,
    TERMINAL_STATUSES,
    JobRecord,
)


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _next_schedule(kind: str, value: str, timezone: str, after: float) -> float:
    if kind == "interval":
        seconds = max(1, int(value))
        return after + seconds
    if kind != "daily":
        raise ValueError(f"unsupported schedule kind: {kind}")
    try:
        tz = ZoneInfo(timezone)
    except ZoneInfoNotFoundError:
        tz = ZoneInfo("UTC")
    hour_text, minute_text = value.split(":", 1)
    now = datetime.fromtimestamp(after, tz)
    target = now.replace(hour=int(hour_text), minute=int(minute_text), second=0, microsecond=0)
    if target.timestamp() <= after:
        target += timedelta(days=1)
    return target.timestamp()


class JobStore:
    def __init__(self, path: Path | str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=5000")
        return conn

    def _init_schema(self) -> None:
        with self._connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    lane TEXT NOT NULL,
                    status TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    result_json TEXT,
                    error TEXT,
                    priority INTEGER NOT NULL DEFAULT 0,
                    dedupe_key TEXT,
                    resource_key TEXT,
                    parent_id TEXT REFERENCES jobs(id) ON DELETE SET NULL,
                    origin TEXT NOT NULL DEFAULT 'system',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 3,
                    run_after REAL NOT NULL,
                    lease_owner TEXT,
                    lease_until REAL,
                    cancel_requested INTEGER NOT NULL DEFAULT 0,
                    progress_current INTEGER NOT NULL DEFAULT 0,
                    progress_total INTEGER NOT NULL DEFAULT 0,
                    progress_message TEXT NOT NULL DEFAULT '',
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    started_at REAL,
                    finished_at REAL
                );
                CREATE INDEX IF NOT EXISTS idx_jobs_claim
                    ON jobs(status, lane, run_after, priority DESC, created_at);
                CREATE INDEX IF NOT EXISTS idx_jobs_parent ON jobs(parent_id, created_at);
                CREATE INDEX IF NOT EXISTS idx_jobs_updated ON jobs(updated_at DESC);
                CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_active_dedupe
                    ON jobs(dedupe_key)
                    WHERE dedupe_key IS NOT NULL
                      AND status IN ('queued', 'running', 'retry_wait', 'cancelling');

                CREATE TABLE IF NOT EXISTS job_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    data_json TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_job_events_job ON job_events(job_id, id);

                CREATE TABLE IF NOT EXISTS schedules (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    task_type TEXT NOT NULL,
                    lane TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 3,
                    schedule_kind TEXT NOT NULL,
                    schedule_value TEXT NOT NULL,
                    timezone TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    next_run REAL NOT NULL,
                    last_job_id TEXT,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_schedules_due ON schedules(enabled, next_run);

                CREATE TABLE IF NOT EXISTS workers (
                    id TEXT PRIMARY KEY,
                    lanes_json TEXT NOT NULL,
                    started_at REAL NOT NULL,
                    last_seen REAL NOT NULL
                );
                """
            )
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    @staticmethod
    def _event(conn: sqlite3.Connection, job_id: str, event_type: str, data: dict[str, Any], now: float) -> None:
        conn.execute(
            "INSERT INTO job_events(job_id,event_type,data_json,created_at) VALUES(?,?,?,?)",
            (job_id, event_type, _dump(data), now),
        )

    def enqueue(
        self,
        task_type: str,
        payload: dict[str, Any],
        *,
        lane: str = "default",
        priority: int = 0,
        dedupe_key: str | None = None,
        resource_key: str | None = None,
        parent_id: str | None = None,
        origin: str = "system",
        max_attempts: int = 3,
        run_after: float | None = None,
    ) -> tuple[JobRecord, bool]:
        now = time.time()
        job_id = uuid.uuid4().hex
        due = now if run_after is None else run_after
        payload_json = _dump(payload)
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                conn.execute(
                    """INSERT INTO jobs(
                        id,type,lane,status,payload_json,priority,dedupe_key,resource_key,parent_id,
                        origin,max_attempts,run_after,created_at,updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (
                        job_id, task_type, lane, QUEUED, payload_json, priority, dedupe_key,
                        resource_key, parent_id, origin, max(1, max_attempts), due, now, now,
                    ),
                )
                self._event(conn, job_id, "queued", {"origin": origin}, now)
                conn.commit()
                row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
                return JobRecord.from_row(row), True
            except sqlite3.IntegrityError:
                conn.rollback()
                if not dedupe_key:
                    raise
                placeholders = ",".join("?" for _ in ACTIVE_STATUSES)
                row = conn.execute(
                    f"SELECT * FROM jobs WHERE dedupe_key=? AND status IN ({placeholders}) "
                    "ORDER BY created_at DESC LIMIT 1",
                    (dedupe_key, *ACTIVE_STATUSES),
                ).fetchone()
                if row is None:
                    raise
                return JobRecord.from_row(row), False

    def get(self, job_id: str) -> JobRecord | None:
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return JobRecord.from_row(row) if row else None

    def list_jobs(
        self,
        *,
        statuses: set[str] | None = None,
        task_type: str | None = None,
        parent_id: str | None = None,
        include_children: bool = False,
        limit: int = 50,
        offset: int = 0,
    ) -> list[JobRecord]:
        where: list[str] = []
        params: list[Any] = []
        if statuses:
            where.append(f"status IN ({','.join('?' for _ in statuses)})")
            params.extend(sorted(statuses))
        if task_type:
            where.append("type=?")
            params.append(task_type)
        if parent_id is not None:
            where.append("parent_id=?")
            params.append(parent_id)
        elif not include_children:
            where.append("parent_id IS NULL")
        sql = "SELECT * FROM jobs"
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " ORDER BY created_at DESC LIMIT ? OFFSET ?"
        params.extend([max(1, min(limit, 200)), max(0, offset)])
        with self._connect() as conn:
            rows = conn.execute(sql, params).fetchall()
        return [JobRecord.from_row(row) for row in rows]

    def latest_by_type(self, task_type: str, *, terminal_only: bool = False) -> JobRecord | None:
        params: list[Any] = [task_type]
        sql = "SELECT * FROM jobs WHERE type=?"
        if terminal_only:
            sql += f" AND status IN ({','.join('?' for _ in TERMINAL_STATUSES)})"
            params.extend(sorted(TERMINAL_STATUSES))
        sql += " ORDER BY created_at DESC LIMIT 1"
        with self._connect() as conn:
            row = conn.execute(sql, params).fetchone()
        return JobRecord.from_row(row) if row else None

    def summary(self, *, include_children: bool = False) -> dict[str, int]:
        where = "" if include_children else " WHERE parent_id IS NULL"
        with self._connect() as conn:
            rows = conn.execute(f"SELECT status,COUNT(*) AS n FROM jobs{where} GROUP BY status").fetchall()
        return {row["status"]: row["n"] for row in rows}

    def child_summary(self, parent_id: str) -> dict[str, int]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT status,COUNT(*) AS n FROM jobs WHERE parent_id=? GROUP BY status", (parent_id,)
            ).fetchall()
        return {row["status"]: row["n"] for row in rows}

    def get_statuses(self, job_ids: set[str]) -> dict[str, str]:
        if not job_ids:
            return {}
        result: dict[str, str] = {}
        ids = list(job_ids)
        with self._connect() as conn:
            for start in range(0, len(ids), 500):
                chunk = ids[start:start + 500]
                rows = conn.execute(
                    f"SELECT id,status FROM jobs WHERE id IN ({','.join('?' for _ in chunk)})", chunk
                ).fetchall()
                result.update({row["id"]: row["status"] for row in rows})
        return result

    def claim(self, lane: str, worker_id: str, lease_seconds: float) -> JobRecord | None:
        now = time.time()
        self.recover_expired(now=now)
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                """SELECT id FROM jobs
                   WHERE lane=? AND status IN (?,?) AND cancel_requested=0 AND run_after<=?
                   ORDER BY priority DESC, created_at ASC LIMIT 1""",
                (lane, QUEUED, RETRY_WAIT, now),
            ).fetchone()
            if row is None:
                conn.commit()
                return None
            job_id = row["id"]
            conn.execute(
                """UPDATE jobs SET status=?,attempts=attempts+1,error=NULL,lease_owner=?,lease_until=?,
                   started_at=COALESCE(started_at,?),updated_at=? WHERE id=?""",
                (RUNNING, worker_id, now + lease_seconds, now, now, job_id),
            )
            self._event(conn, job_id, "running", {"worker": worker_id}, now)
            conn.commit()
            claimed = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return JobRecord.from_row(claimed)

    def recover_expired(self, *, now: float | None = None) -> int:
        current = time.time() if now is None else now
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            rows = conn.execute(
                """SELECT id,attempts,max_attempts,cancel_requested FROM jobs
                   WHERE status IN (?,?) AND lease_until<?""",
                (RUNNING, CANCELLING, current),
            ).fetchall()
            for row in rows:
                if row["cancel_requested"]:
                    conn.execute(
                        """UPDATE jobs SET status=?,lease_owner=NULL,lease_until=NULL,finished_at=?,
                           updated_at=? WHERE id=?""",
                        (CANCELLED, current, current, row["id"]),
                    )
                    self._event(conn, row["id"], "cancelled", {"reason": "lease_expired"}, current)
                elif row["attempts"] >= row["max_attempts"]:
                    error = f"Worker 租约已过期（已尝试 {row['attempts']} 次）"
                    conn.execute(
                        """UPDATE jobs SET status=?,lease_owner=NULL,lease_until=NULL,finished_at=?,
                           error=?,updated_at=? WHERE id=?""",
                        (FAILED, current, error, current, row["id"]),
                    )
                    self._event(conn, row["id"], "failed", {"reason": "lease_expired", "error": error}, current)
                else:
                    conn.execute(
                        """UPDATE jobs SET status=?,lease_owner=NULL,lease_until=NULL,run_after=?,
                           error=?,updated_at=? WHERE id=?""",
                        (QUEUED, current, "Worker 租约过期，任务已重新排队", current, row["id"]),
                    )
                    self._event(conn, row["id"], "recovered", {}, current)
            conn.commit()
        return len(rows)

    def heartbeat(self, job_id: str, worker_id: str, lease_seconds: float) -> bool | None:
        now = time.time()
        with self._connect() as conn:
            row = conn.execute(
                "SELECT cancel_requested FROM jobs WHERE id=? AND lease_owner=? AND status IN (?,?)",
                (job_id, worker_id, RUNNING, CANCELLING),
            ).fetchone()
            if row is None:
                return None
            conn.execute(
                "UPDATE jobs SET lease_until=?,updated_at=? WHERE id=? AND lease_owner=?",
                (now + lease_seconds, now, job_id, worker_id),
            )
        return bool(row["cancel_requested"])

    def update_progress(self, job_id: str, current: int, total: int, message: str) -> bool:
        now = time.time()
        total = max(0, total)
        current = max(0, min(current, total)) if total else max(0, current)
        message = message[:500]
        with self._connect() as conn:
            changed = conn.execute(
                """UPDATE jobs SET progress_current=?,progress_total=?,progress_message=?,updated_at=?
                   WHERE id=? AND status IN (?,?)
                     AND (progress_current<>? OR progress_total<>? OR progress_message<>?)""",
                (
                    current, total, message, now, job_id, RUNNING, CANCELLING,
                    current, total, message,
                ),
            ).rowcount
            if changed:
                self._event(conn, job_id, "progress", {"current": current, "total": total, "message": message}, now)
        return bool(changed)

    def complete(self, job_id: str, worker_id: str, result: dict[str, Any] | None) -> bool:
        now = time.time()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT cancel_requested FROM jobs WHERE id=? AND lease_owner=? AND status IN (?,?)",
                (job_id, worker_id, RUNNING, CANCELLING),
            ).fetchone()
            if row is None:
                conn.rollback()
                return False
            status = CANCELLED if row["cancel_requested"] else SUCCEEDED
            conn.execute(
                """UPDATE jobs SET status=?,result_json=?,error=NULL,lease_owner=NULL,lease_until=NULL,
                   finished_at=?,updated_at=? WHERE id=?""",
                (status, _dump(result or {}), now, now, job_id),
            )
            self._event(conn, job_id, status, result or {}, now)
            conn.commit()
        return True

    def fail(
        self,
        job_id: str,
        worker_id: str,
        error: str,
        *,
        retryable: bool,
        retry_delay: float,
    ) -> str | None:
        now = time.time()
        error = error[:2000]
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT attempts,max_attempts,cancel_requested FROM jobs "
                "WHERE id=? AND lease_owner=? AND status IN (?,?)",
                (job_id, worker_id, RUNNING, CANCELLING),
            ).fetchone()
            if row is None:
                conn.rollback()
                return None
            if row["cancel_requested"]:
                status = CANCELLED
                run_after = now
                finished_at = now
            elif retryable and row["attempts"] < row["max_attempts"]:
                status = RETRY_WAIT
                run_after = now + max(0, retry_delay)
                finished_at = None
            else:
                status = FAILED
                run_after = now
                finished_at = now
            stored_error = None if status == CANCELLED else error
            conn.execute(
                """UPDATE jobs SET status=?,error=?,run_after=?,lease_owner=NULL,lease_until=NULL,
                   finished_at=?,updated_at=? WHERE id=?""",
                (status, stored_error, run_after, finished_at, now, job_id),
            )
            event_data = (
                {"reason": "cancel_requested"}
                if status == CANCELLED
                else {"error": error, "retryable": retryable}
            )
            self._event(conn, job_id, status, event_data, now)
            conn.commit()
        return status

    def release(self, job_id: str, worker_id: str) -> bool:
        now = time.time()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                """SELECT cancel_requested FROM jobs
                   WHERE id=? AND lease_owner=? AND status IN (?,?)""",
                (job_id, worker_id, RUNNING, CANCELLING),
            ).fetchone()
            if row is None:
                conn.rollback()
                return False
            if row["cancel_requested"]:
                conn.execute(
                    """UPDATE jobs SET status=?,run_after=?,lease_owner=NULL,lease_until=NULL,
                       finished_at=?,updated_at=? WHERE id=?""",
                    (CANCELLED, now, now, now, job_id),
                )
                self._event(conn, job_id, "cancelled", {"reason": "worker_stopped"}, now)
            else:
                conn.execute(
                    """UPDATE jobs SET status=?,run_after=?,lease_owner=NULL,lease_until=NULL,
                       finished_at=NULL,updated_at=? WHERE id=?""",
                    (QUEUED, now, now, job_id),
                )
                self._event(conn, job_id, "released", {}, now)
            conn.commit()
        return True

    def cancel(self, job_id: str) -> JobRecord | None:
        now = time.time()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                conn.rollback()
                return None
            status = row["status"]
            if status in (QUEUED, RETRY_WAIT):
                next_status = CANCELLED
                finished_at = now
            elif status in (RUNNING, CANCELLING):
                next_status = CANCELLING
                finished_at = None
            else:
                conn.commit()
                result = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
                return JobRecord.from_row(result)
            conn.execute(
                """UPDATE jobs SET status=?,cancel_requested=1,finished_at=?,updated_at=? WHERE id=?""",
                (next_status, finished_at, now, job_id),
            )
            self._event(conn, job_id, "cancel_requested", {}, now)
            conn.commit()
            result = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return JobRecord.from_row(result)

    def cancel_children(self, parent_id: str) -> int:
        placeholders = ",".join("?" for _ in ACTIVE_STATUSES)
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT id FROM jobs WHERE parent_id=? AND status IN ({placeholders})",
                (parent_id, *ACTIVE_STATUSES),
            ).fetchall()
        for row in rows:
            self.cancel(row["id"])
        return len(rows)

    def retry(self, job_id: str) -> JobRecord | None:
        now = time.time()
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None or row["status"] not in (FAILED, CANCELLED):
                conn.rollback()
                return None
            try:
                conn.execute(
                    """UPDATE jobs SET status=?,attempts=0,error=NULL,result_json=NULL,cancel_requested=0,
                       run_after=?,lease_owner=NULL,lease_until=NULL,started_at=NULL,finished_at=NULL,
                       progress_current=0,progress_total=0,progress_message='',updated_at=? WHERE id=?""",
                    (QUEUED, now, now, job_id),
                )
            except sqlite3.IntegrityError:
                conn.rollback()
                return None
            self._event(conn, job_id, "retried", {}, now)
            conn.commit()
            result = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return JobRecord.from_row(result)

    def list_events(self, after_id: int = 0, limit: int = 100) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id,job_id,event_type,data_json,created_at FROM job_events "
                "WHERE id>? ORDER BY id ASC LIMIT ?",
                (max(0, after_id), max(1, min(limit, 500))),
            ).fetchall()
        events = []
        for row in rows:
            try:
                data = json.loads(row["data_json"])
            except ValueError:
                data = {}
            events.append({
                "id": row["id"], "job_id": row["job_id"], "type": row["event_type"],
                "data": data, "created_at": row["created_at"],
            })
        return events

    def latest_event_id(self) -> int:
        with self._connect() as conn:
            row = conn.execute("SELECT COALESCE(MAX(id), 0) AS id FROM job_events").fetchone()
        return int(row["id"])

    def prune_history(self, *, older_than_days: int = 30, keep_recent: int = 2000) -> dict[str, int]:
        cutoff = time.time() - max(1, older_than_days) * 86400
        statuses = sorted(TERMINAL_STATUSES)
        placeholders = ",".join("?" for _ in statuses)
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            rows = conn.execute(
                f"""SELECT id FROM jobs
                    WHERE status IN ({placeholders}) AND COALESCE(finished_at,updated_at)<?
                      AND id NOT IN (
                        SELECT id FROM jobs WHERE status IN ({placeholders})
                        ORDER BY COALESCE(finished_at,updated_at) DESC LIMIT ?
                      )""",
                (*statuses, cutoff, *statuses, max(1, keep_recent)),
            ).fetchall()
            job_ids = [row["id"] for row in rows]
            candidate_ids = set(job_ids)
            protected: set[str] = set()
            parent_of: dict[str, str] = {}
            for start in range(0, len(job_ids), 500):
                chunk = job_ids[start:start + 500]
                marks = ",".join("?" for _ in chunk)
                candidate_rows = conn.execute(
                    f"SELECT id,parent_id FROM jobs WHERE id IN ({marks})", chunk
                ).fetchall()
                parent_of.update({
                    row["id"]: row["parent_id"] for row in candidate_rows if row["parent_id"]
                })
                child_rows = conn.execute(
                    f"SELECT id,parent_id FROM jobs WHERE parent_id IN ({marks})", chunk
                ).fetchall()
                protected.update(
                    row["parent_id"] for row in child_rows if row["id"] not in candidate_ids
                )
            changed = True
            while changed:
                changed = False
                for child_id, parent_id in parent_of.items():
                    if child_id in protected and parent_id in candidate_ids and parent_id not in protected:
                        protected.add(parent_id)
                        changed = True
            if protected:
                job_ids = [job_id for job_id in job_ids if job_id not in protected]
            removed_events = 0
            for start in range(0, len(job_ids), 500):
                chunk = job_ids[start:start + 500]
                marks = ",".join("?" for _ in chunk)
                removed_events += conn.execute(
                    f"DELETE FROM job_events WHERE job_id IN ({marks})", chunk
                ).rowcount
                conn.execute(f"DELETE FROM jobs WHERE id IN ({marks})", chunk)
            removed_workers = conn.execute(
                "DELETE FROM workers WHERE last_seen<?", (time.time() - 86400,)
            ).rowcount
            conn.commit()
        return {
            "jobs": len(job_ids),
            "events": removed_events,
            "workers": removed_workers,
        }

    def upsert_schedule(
        self,
        schedule_id: str,
        name: str,
        task_type: str,
        payload: dict[str, Any],
        *,
        lane: str,
        schedule_kind: str,
        schedule_value: str,
        timezone: str,
        priority: int = 0,
        max_attempts: int = 3,
        enabled: bool = True,
    ) -> None:
        now = time.time()
        next_run = _next_schedule(schedule_kind, schedule_value, timezone, now)
        with self._connect() as conn:
            conn.execute(
                """INSERT INTO schedules(
                    id,name,task_type,lane,payload_json,priority,max_attempts,schedule_kind,
                    schedule_value,timezone,enabled,next_run,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name,task_type=excluded.task_type,lane=excluded.lane,
                    payload_json=excluded.payload_json,priority=excluded.priority,
                    max_attempts=excluded.max_attempts,schedule_kind=excluded.schedule_kind,
                    schedule_value=excluded.schedule_value,timezone=excluded.timezone,
                    enabled=excluded.enabled,updated_at=excluded.updated_at""",
                (
                    schedule_id, name, task_type, lane, _dump(payload), priority, max_attempts,
                    schedule_kind, schedule_value, timezone, int(enabled), next_run, now, now,
                ),
            )

    def dispatch_due_schedules(self, *, now: float | None = None, limit: int = 20) -> list[JobRecord]:
        current = time.time() if now is None else now
        created: list[JobRecord] = []
        with self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            rows = conn.execute(
                "SELECT * FROM schedules WHERE enabled=1 AND next_run<=? ORDER BY next_run LIMIT ?",
                (current, max(1, min(limit, 100))),
            ).fetchall()
            for row in rows:
                scheduled_for = row["next_run"]
                job_id = uuid.uuid4().hex
                dedupe_key = f"schedule:{row['id']}:{int(scheduled_for)}"
                try:
                    conn.execute(
                        """INSERT INTO jobs(
                            id,type,lane,status,payload_json,priority,dedupe_key,origin,max_attempts,
                            run_after,created_at,updated_at
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                        (
                            job_id, row["task_type"], row["lane"], QUEUED, row["payload_json"],
                            row["priority"], dedupe_key, "schedule", row["max_attempts"],
                            current, current, current,
                        ),
                    )
                    self._event(conn, job_id, "queued", {"origin": "schedule", "schedule_id": row["id"]}, current)
                except sqlite3.IntegrityError:
                    existing = conn.execute(
                        "SELECT id FROM jobs WHERE dedupe_key=? ORDER BY created_at DESC LIMIT 1", (dedupe_key,)
                    ).fetchone()
                    job_id = existing["id"] if existing else job_id
                next_run = _next_schedule(
                    row["schedule_kind"], row["schedule_value"], row["timezone"],
                    max(scheduled_for, current),
                )
                conn.execute(
                    "UPDATE schedules SET next_run=?,last_job_id=?,updated_at=? WHERE id=?",
                    (next_run, job_id, current, row["id"]),
                )
            conn.commit()
            for row in rows:
                job_row = conn.execute(
                    "SELECT j.* FROM jobs j JOIN schedules s ON s.last_job_id=j.id WHERE s.id=?", (row["id"],)
                ).fetchone()
                if job_row:
                    created.append(JobRecord.from_row(job_row))
        return created

    def heartbeat_worker(self, worker_id: str, lanes: dict[str, int], *, started_at: float) -> None:
        now = time.time()
        with self._connect() as conn:
            conn.execute(
                """INSERT INTO workers(id,lanes_json,started_at,last_seen) VALUES(?,?,?,?)
                   ON CONFLICT(id) DO UPDATE SET lanes_json=excluded.lanes_json,last_seen=excluded.last_seen""",
                (worker_id, _dump(lanes), started_at, now),
            )

    def remove_worker(self, worker_id: str) -> None:
        with self._connect() as conn:
            conn.execute("DELETE FROM workers WHERE id=?", (worker_id,))

    def active_workers(self, *, max_age: float = 30.0) -> list[dict[str, Any]]:
        cutoff = time.time() - max_age
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT id,lanes_json,started_at,last_seen FROM workers WHERE last_seen>=? ORDER BY started_at",
                (cutoff,),
            ).fetchall()
        result = []
        for row in rows:
            try:
                lanes = json.loads(row["lanes_json"])
            except ValueError:
                lanes = {}
            result.append({"id": row["id"], "lanes": lanes, "started_at": row["started_at"], "last_seen": row["last_seen"]})
        return result
