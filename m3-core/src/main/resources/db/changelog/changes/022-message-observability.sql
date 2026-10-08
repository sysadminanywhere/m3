ALTER TABLE message ADD COLUMN IF NOT EXISTS search_text TEXT;
ALTER TABLE message ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(200);
ALTER TABLE message ADD COLUMN IF NOT EXISTS search_version VARCHAR(64);
ALTER TABLE message ADD COLUMN IF NOT EXISTS archive_key VARCHAR(500);
ALTER TABLE message ADD COLUMN IF NOT EXISTS archive_sha256 VARCHAR(64);
ALTER TABLE message ADD COLUMN IF NOT EXISTS stored_size INTEGER;
ALTER TABLE rule_execution_job ADD COLUMN IF NOT EXISTS configuration_hash VARCHAR(64);
ALTER TABLE message ALTER COLUMN payload_bytes DROP NOT NULL;
UPDATE message SET stored_size=octet_length(payload_bytes);
CREATE INDEX message_body_search ON message USING gin (lower(search_text) public.gin_trgm_ops);
CREATE INDEX message_correlation ON message(correlation_id);

CREATE TABLE message_event (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES message(message_id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    kind VARCHAR(60) NOT NULL,
    job_id BIGINT,
    rule_id BIGINT,
    pool VARCHAR(80),
    worker VARCHAR(160),
    attempt INTEGER,
    status VARCHAR(30),
    detail TEXT,
    actor VARCHAR(200) NOT NULL DEFAULT 'system',
    related_message_id BIGINT,
    configuration_hash VARCHAR(64)
);
CREATE INDEX message_event_time ON message_event(message_id,occurred_at,event_id);
CREATE TABLE message_delivery_snapshot (
    job_id BIGINT PRIMARY KEY REFERENCES rule_execution_job(job_id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES message(message_id) ON DELETE CASCADE,
    payload_bytes BYTEA,
    payload_type VARCHAR(255) NOT NULL,
    charset VARCHAR(64),
    metadata TEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    stored_size INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE message_archive_job (
    message_id BIGINT PRIMARY KEY REFERENCES message(message_id) ON DELETE CASCADE,
    object_key VARCHAR(500) NOT NULL,
    claim_token UUID,
    claimed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(100)
);

-- Capture all job transitions, including recovery after worker loss, in the same transaction.
CREATE FUNCTION m3_job_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR ROW(NEW.status,NEW.attempts,NEW.delivery_started,NEW.delivery_uncertain,NEW.error_message)
       IS DISTINCT FROM ROW(OLD.status,OLD.attempts,OLD.delivery_started,OLD.delivery_uncertain,OLD.error_message) THEN
        INSERT INTO message_event(message_id,kind,job_id,rule_id,pool,worker,attempt,status,detail,configuration_hash)
        VALUES(NEW.message_id,
            CASE WHEN TG_OP='INSERT' THEN 'QUEUED'
                 WHEN NEW.delivery_started AND NOT OLD.delivery_started THEN 'DELIVERY_STARTED'
                 WHEN NEW.status='PROCESSING' THEN 'ATTEMPT_STARTED'
                 WHEN NEW.delivery_uncertain THEN 'DELIVERY_UNCERTAIN'
                 WHEN NEW.status='COMPLETED' THEN 'ATTEMPT_COMPLETED'
                 WHEN NEW.status='FAILED' THEN 'ATTEMPT_FAILED'
                 ELSE 'REQUEUED' END,
            NEW.job_id,NEW.rule_id,(SELECT name FROM rule_worker_pool WHERE worker_pool_id=NEW.worker_pool_id),
            NEW.worker_id,NEW.attempts,NEW.status,NEW.error_message,
            NEW.configuration_hash);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER m3_job_history AFTER INSERT OR UPDATE ON rule_execution_job
FOR EACH ROW EXECUTE FUNCTION m3_job_history();
CREATE FUNCTION m3_message_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO message_event(message_id,kind,status)
        VALUES(NEW.message_id,CASE WHEN TG_OP='INSERT' THEN 'RECEIVED' ELSE 'MESSAGE_STATUS' END,NEW.status);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER m3_message_history AFTER INSERT OR UPDATE ON message
FOR EACH ROW EXECUTE FUNCTION m3_message_history();
INSERT INTO message_event(message_id,kind,status,detail)
SELECT message_id,'HISTORY_BASELINE',status,'History recording starts at this upgrade; earlier attempts are unavailable.' FROM message;
