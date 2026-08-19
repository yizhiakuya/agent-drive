ALTER TABLE tasks
    ADD COLUMN user_id uuid,
    ADD COLUMN priority integer NOT NULL DEFAULT 0,
    ADD COLUMN resource_key text,
    ADD COLUMN origin text NOT NULL DEFAULT 'api',
    ADD COLUMN cancel_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN progress_current integer NOT NULL DEFAULT 0,
    ADD COLUMN progress_total integer NOT NULL DEFAULT 0,
    ADD COLUMN progress_message text NOT NULL DEFAULT '',
    ADD COLUMN started_at timestamptz,
    ADD COLUMN finished_at timestamptz;

DO $$
DECLARE
    missing_tasks integer;
    user_count integer;
    owner_id uuid;
BEGIN
    SELECT COUNT(*) INTO missing_tasks FROM tasks WHERE user_id IS NULL;
    IF missing_tasks > 0 THEN
        SELECT COUNT(*), min(id) INTO user_count, owner_id FROM users;
        IF user_count <> 1 THEN
            RAISE EXCEPTION 'cannot owner-scope legacy task metadata: expected exactly one user, found %', user_count;
        END IF;
        UPDATE tasks SET user_id = owner_id WHERE user_id IS NULL;
    END IF;
END $$;

ALTER TABLE tasks
    ADD CONSTRAINT tasks_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE tasks
    ALTER COLUMN user_id SET NOT NULL;
CREATE INDEX tasks_user_status_idx ON tasks(user_id, status, available_at);
CREATE INDEX tasks_user_updated_idx ON tasks(user_id, updated_at DESC);

ALTER TABLE task_schedules
    ADD COLUMN user_id uuid;

DO $$
DECLARE
    missing_schedules integer;
    user_count integer;
    owner_id uuid;
BEGIN
    SELECT COUNT(*) INTO missing_schedules FROM task_schedules WHERE user_id IS NULL;
    IF missing_schedules > 0 THEN
        SELECT COUNT(*), min(id) INTO user_count, owner_id FROM users;
        IF user_count <> 1 THEN
            RAISE EXCEPTION 'cannot owner-scope legacy task schedules: expected exactly one user, found %', user_count;
        END IF;
        UPDATE task_schedules SET user_id = owner_id WHERE user_id IS NULL;
    END IF;
END $$;

ALTER TABLE task_schedules
    ADD CONSTRAINT task_schedules_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE task_schedules
    ALTER COLUMN user_id SET NOT NULL;
CREATE INDEX task_schedules_user_enabled_idx
    ON task_schedules(user_id, enabled, next_run_at);
