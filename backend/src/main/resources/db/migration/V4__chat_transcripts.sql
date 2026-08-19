ALTER TABLE chat_sessions
    ADD COLUMN last_trace jsonb;

CREATE TABLE chat_tool_replays (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    tool_name text NOT NULL,
    arguments jsonb NOT NULL,
    output text NOT NULL,
    parsed jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX chat_tool_replays_exact_idx
    ON chat_tool_replays(session_id, tool_name, arguments);

INSERT INTO chat_tool_replays(session_id, tool_name, arguments, output, parsed, created_at)
SELECT session_id, tool_name, arguments, COALESCE(content, ''), parsed, created_at
FROM chat_messages
WHERE role = 'tool_call'
  AND tool_name IS NOT NULL
  AND arguments IS NOT NULL
ON CONFLICT (session_id, tool_name, arguments) DO NOTHING;
