CREATE TABLE IF NOT EXISTS chat_run_events (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    event_name text NOT NULL,
    event_data jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS chat_run_events_session_idx ON chat_run_events(session_id, id);
