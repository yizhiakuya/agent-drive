CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username text NOT NULL UNIQUE,
    password_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user_active_idx ON sessions(user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_token_hash text NOT NULL UNIQUE,
    name text NOT NULL,
    platform text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);
CREATE INDEX devices_user_active_idx ON devices(user_id) WHERE revoked_at IS NULL;

CREATE TABLE pairing_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE chat_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title text,
    summary text,
    last_routed text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chat_sessions_user_updated_idx ON chat_sessions(user_id, updated_at DESC);

CREATE TABLE chat_messages (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('user', 'assistant', 'tool_call')),
    content text,
    reasoning text,
    tool_name text,
    arguments jsonb,
    parsed jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chat_messages_session_idx ON chat_messages(session_id, id);

CREATE TABLE files (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    path text NOT NULL UNIQUE,
    is_dir boolean NOT NULL,
    size_bytes bigint NOT NULL DEFAULT 0,
    revision bigint NOT NULL DEFAULT 1,
    content_md5 text,
    content_sha256 text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX files_path_prefix_idx ON files(path text_pattern_ops);

CREATE TABLE file_revisions (
    id bigserial PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    size_bytes bigint NOT NULL DEFAULT 0,
    content_md5 text,
    content_sha256 text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (file_id, revision)
);

CREATE TABLE trash_entries (
    trash_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    original_path text NOT NULL,
    stored_path text NOT NULL,
    file_revision bigint NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz
);
CREATE INDEX trash_entries_expiry_idx ON trash_entries(expires_at);

CREATE TABLE upload_dedup (
    content_md5 text PRIMARY KEY,
    path text NOT NULL,
    file_revision bigint NOT NULL,
    verified boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tasks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id uuid REFERENCES tasks(id) ON DELETE SET NULL,
    kind text NOT NULL,
    lane text NOT NULL,
    status text NOT NULL CHECK (status IN ('queued', 'running', 'retry_wait', 'cancelling', 'cancelled', 'succeeded', 'failed')),
    dedupe_key text,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    result jsonb,
    error text,
    attempt integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    lease_owner text,
    lease_until timestamptz,
    heartbeat_at timestamptz,
    available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX tasks_active_dedupe_idx ON tasks(dedupe_key)
    WHERE dedupe_key IS NOT NULL AND status IN ('queued', 'running', 'retry_wait', 'cancelling');
CREATE INDEX tasks_claim_idx ON tasks(status, available_at, lane);

CREATE TABLE task_events (
    id bigserial PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    event_type text NOT NULL,
    progress integer,
    message text,
    payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX task_events_task_idx ON task_events(task_id, id);

CREATE TABLE task_schedules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL UNIQUE,
    cron text NOT NULL,
    task_kind text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    enabled boolean NOT NULL DEFAULT true,
    next_run_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id bigserial PRIMARY KEY,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    idempotency_key text NOT NULL UNIQUE,
    payload jsonb NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_unpublished_idx ON outbox_events(id) WHERE published_at IS NULL;

CREATE TABLE documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id uuid NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    source_revision bigint NOT NULL,
    extractor_version text NOT NULL,
    content text NOT NULL DEFAULT '',
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (file_id, source_revision, extractor_version)
);
CREATE INDEX documents_content_fts_idx ON documents USING gin (to_tsvector('simple', content));

CREATE TABLE document_chunks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index integer NOT NULL,
    source_revision bigint NOT NULL,
    chunk_version text NOT NULL,
    content text NOT NULL,
    embedding_fingerprint text,
    embedding vector,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index, chunk_version)
);
CREATE INDEX document_chunks_document_idx ON document_chunks(document_id, chunk_index);

CREATE TABLE agent_preferences (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    preference_key text NOT NULL,
    value jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, preference_key)
);

CREATE TABLE llm_provider_configs (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    provider text NOT NULL,
    base_url text NOT NULL,
    model text NOT NULL,
    encrypted_api_key bytea,
    api_key_fingerprint text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
