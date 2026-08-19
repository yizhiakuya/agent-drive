ALTER TABLE outbox_events
    ADD COLUMN user_id uuid;

DO $$
DECLARE
    missing_events integer;
    user_count integer;
    owner_id uuid;
BEGIN
    SELECT COUNT(*) INTO missing_events FROM outbox_events WHERE user_id IS NULL;
    IF missing_events > 0 THEN
        SELECT COUNT(*), min(id) INTO user_count, owner_id FROM users;
        IF user_count <> 1 THEN
            RAISE EXCEPTION 'cannot owner-scope legacy outbox events: expected exactly one user, found %', user_count;
        END IF;
        UPDATE outbox_events SET user_id = owner_id WHERE user_id IS NULL;
    END IF;
END $$;

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE outbox_events
    ALTER COLUMN user_id SET NOT NULL;
CREATE INDEX outbox_user_unpublished_idx
    ON outbox_events(user_id, id) WHERE published_at IS NULL;
