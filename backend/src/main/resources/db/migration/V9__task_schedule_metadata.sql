ALTER TABLE task_schedules
    ADD COLUMN schedule_kind text NOT NULL DEFAULT 'cron',
    ADD COLUMN schedule_value text,
    ADD COLUMN lane text NOT NULL DEFAULT 'default',
    ADD COLUMN priority integer NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts integer NOT NULL DEFAULT 3,
    ADD COLUMN timezone text NOT NULL DEFAULT 'UTC',
    ADD COLUMN last_task_id uuid REFERENCES tasks(id) ON DELETE SET NULL,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

UPDATE task_schedules
SET schedule_value = cron
WHERE schedule_value IS NULL;

ALTER TABLE task_schedules
    ALTER COLUMN schedule_value SET NOT NULL;

ALTER TABLE task_schedules
    DROP CONSTRAINT IF EXISTS task_schedules_name_key;
CREATE UNIQUE INDEX task_schedules_user_name_idx ON task_schedules(user_id, name);
CREATE INDEX task_schedules_due_idx ON task_schedules(user_id, enabled, next_run_at);
