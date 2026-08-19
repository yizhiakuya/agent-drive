ALTER TABLE devices
    ALTER COLUMN device_token_hash DROP NOT NULL;

ALTER TABLE devices
    ADD COLUMN model text NOT NULL DEFAULT '',
    ADD COLUMN app_version text NOT NULL DEFAULT '',
    ADD COLUMN sync_state jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX devices_user_last_seen_idx
    ON devices(user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL AND external_device_id IS NOT NULL;
