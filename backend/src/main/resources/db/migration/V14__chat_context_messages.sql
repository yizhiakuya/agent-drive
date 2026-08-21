ALTER TABLE chat_messages
    DROP CONSTRAINT chat_messages_role_check;

ALTER TABLE chat_messages
    ADD COLUMN context_source text,
    ADD COLUMN context_kind text;

ALTER TABLE chat_messages
    ADD CONSTRAINT chat_messages_role_check
        CHECK (role IN ('user', 'assistant', 'tool_call', 'context')),
    ADD CONSTRAINT chat_messages_context_fields
        CHECK (
            (role = 'context' AND context_source IS NOT NULL AND context_kind IS NOT NULL)
            OR
            (role <> 'context' AND context_source IS NULL AND context_kind IS NULL)
        ),
    ADD CONSTRAINT chat_messages_context_source_length
        CHECK (context_source IS NULL OR char_length(context_source) BETWEEN 1 AND 128),
    ADD CONSTRAINT chat_messages_context_kind_length
        CHECK (context_kind IS NULL OR char_length(context_kind) BETWEEN 1 AND 64);

CREATE INDEX chat_messages_context_latest_idx
    ON chat_messages(session_id, context_source, id DESC)
    WHERE role = 'context';
