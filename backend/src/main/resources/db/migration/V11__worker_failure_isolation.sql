ALTER TABLE task_schedules
    ADD COLUMN last_error text;

ALTER TABLE outbox_events
    ADD COLUMN failure_count integer NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    ADD COLUMN last_error text,
    ADD COLUMN dead_lettered_at timestamptz;

DROP INDEX IF EXISTS outbox_unpublished_idx;
DROP INDEX IF EXISTS outbox_user_unpublished_idx;

CREATE INDEX outbox_unpublished_idx
    ON outbox_events(id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
CREATE INDEX outbox_user_unpublished_idx
    ON outbox_events(user_id, id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
