ALTER TABLE chat_sessions
    ADD COLUMN IF NOT EXISTS run_state jsonb NOT NULL
        DEFAULT '{"status":"idle","phase":"idle","resumable":false}'::jsonb;
