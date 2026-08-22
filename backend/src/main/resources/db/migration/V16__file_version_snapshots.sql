CREATE TABLE file_version_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    path text NOT NULL,
    source_revision bigint NOT NULL,
    snapshot_path text NOT NULL,
    size_bytes bigint NOT NULL,
    content_md5 text,
    content_sha256 text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, snapshot_path)
);

CREATE INDEX file_version_snapshots_user_path_idx
    ON file_version_snapshots(user_id, path, created_at DESC);
