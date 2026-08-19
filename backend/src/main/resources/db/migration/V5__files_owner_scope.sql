ALTER TABLE files
    ADD COLUMN user_id uuid REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE trash_entries
    ADD COLUMN user_id uuid REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE upload_dedup
    ADD COLUMN user_id uuid REFERENCES users(id) ON DELETE CASCADE;

DO $$
DECLARE
    legacy_user uuid;
    user_count integer;
BEGIN
    IF EXISTS (SELECT 1 FROM files WHERE user_id IS NULL)
       OR EXISTS (SELECT 1 FROM trash_entries WHERE user_id IS NULL)
       OR EXISTS (SELECT 1 FROM upload_dedup WHERE user_id IS NULL) THEN
        SELECT count(*), min(id) INTO user_count, legacy_user FROM users;
        IF user_count <> 1 THEN
            RAISE EXCEPTION 'cannot owner-scope legacy file metadata: expected exactly one user, found %', user_count;
        END IF;
        UPDATE files SET user_id = legacy_user WHERE user_id IS NULL;
        UPDATE trash_entries SET user_id = legacy_user WHERE user_id IS NULL;
        UPDATE upload_dedup SET user_id = legacy_user WHERE user_id IS NULL;
    END IF;
END
$$;

ALTER TABLE files ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE trash_entries ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE upload_dedup ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE files DROP CONSTRAINT files_path_key;
CREATE UNIQUE INDEX files_user_path_unique_idx ON files(user_id, path);

ALTER TABLE upload_dedup DROP CONSTRAINT upload_dedup_pkey;
ALTER TABLE upload_dedup ADD PRIMARY KEY (user_id, content_md5);

CREATE INDEX files_user_path_idx ON files(user_id, path text_pattern_ops);
CREATE INDEX trash_entries_user_idx ON trash_entries(user_id, deleted_at DESC);
CREATE INDEX upload_dedup_user_idx ON upload_dedup(user_id, content_md5);
