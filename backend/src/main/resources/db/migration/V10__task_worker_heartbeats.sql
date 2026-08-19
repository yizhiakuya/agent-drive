CREATE TABLE task_workers (
    worker_id text PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    heartbeat_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX task_workers_heartbeat_idx ON task_workers(heartbeat_at);
