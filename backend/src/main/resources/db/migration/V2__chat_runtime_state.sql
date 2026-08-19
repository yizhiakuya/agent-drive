ALTER TABLE chat_sessions
    ADD COLUMN pending_confirmation jsonb;

ALTER TABLE chat_sessions
    ADD COLUMN consumed_confirmation_nonces jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX chat_messages_tool_replay_idx
    ON chat_messages(session_id, tool_name, created_at DESC)
    WHERE role = 'tool_call';
