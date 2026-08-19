ALTER TABLE devices
    ADD COLUMN external_device_id text;

CREATE UNIQUE INDEX devices_user_external_device_idx
    ON devices(user_id, external_device_id)
    WHERE external_device_id IS NOT NULL;

ALTER TABLE pairing_codes
    ADD COLUMN user_id uuid REFERENCES users(id) ON DELETE CASCADE;

CREATE INDEX pairing_codes_user_active_idx
    ON pairing_codes(user_id, expires_at)
    WHERE consumed_at IS NULL;
