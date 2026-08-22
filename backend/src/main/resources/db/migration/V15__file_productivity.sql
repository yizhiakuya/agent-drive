CREATE TABLE file_favorites (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    path text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, path)
);
CREATE INDEX file_favorites_user_created_idx
    ON file_favorites(user_id, created_at DESC);

CREATE TABLE file_accesses (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    path text NOT NULL,
    last_accessed_at timestamptz NOT NULL DEFAULT now(),
    access_count bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, path)
);
CREATE INDEX file_accesses_user_recent_idx
    ON file_accesses(user_id, last_accessed_at DESC);
