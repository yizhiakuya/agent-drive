DROP INDEX IF EXISTS tasks_active_dedupe_idx;

CREATE UNIQUE INDEX tasks_active_dedupe_idx ON tasks(user_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL
      AND status IN ('queued', 'running', 'retry_wait', 'cancelling');
