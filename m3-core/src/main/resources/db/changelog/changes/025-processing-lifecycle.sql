ALTER TABLE message ADD COLUMN IF NOT EXISTS source_channel_id BIGINT;
ALTER TABLE message_event ADD COLUMN processing_attempt_id UUID;
ALTER TABLE message_event ADD COLUMN recipient VARCHAR(80);
UPDATE message m SET source_channel_id=c.channel_id FROM message_metadata md JOIN channel_settings c ON c.channel_id::text=md.value
 WHERE md.message_id=m.message_id AND md.key='sourceChannelId' AND m.source_channel_id IS NULL;
CREATE TABLE message_processing (
 attempt_id UUID PRIMARY KEY, message_id BIGINT NOT NULL REFERENCES message(message_id) ON DELETE CASCADE,
 recipient VARCHAR(80) NOT NULL, required BOOLEAN NOT NULL, attempt_no INTEGER NOT NULL, is_current BOOLEAN NOT NULL DEFAULT true,
 status VARCHAR(30) NOT NULL CHECK(status IN ('LOADED','PROCESSING','PROCESSED','PROCESSING_FAILED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
 deadline_at TIMESTAMPTZ, overdue BOOLEAN NOT NULL DEFAULT false, detail TEXT,
 loaded_timeout_seconds INTEGER NOT NULL, processing_timeout_seconds INTEGER NOT NULL,
 UNIQUE(message_id,recipient,attempt_no)
);
CREATE UNIQUE INDEX processing_current ON message_processing(message_id,recipient) WHERE is_current;
CREATE INDEX processing_deadline ON message_processing(deadline_at) WHERE is_current AND NOT overdue AND status IN ('LOADED','PROCESSING');
CREATE TABLE processing_callback (
 callback_id UUID PRIMARY KEY, attempt_id UUID NOT NULL REFERENCES message_processing(attempt_id) ON DELETE CASCADE,
 request_digest VARCHAR(64) NOT NULL, response TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE processing_replay (
 request_id UUID PRIMARY KEY, message_id BIGINT NOT NULL REFERENCES message(message_id) ON DELETE CASCADE,
 recipient VARCHAR(80) NOT NULL, request_digest VARCHAR(64) NOT NULL, response TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE processing_event_outbox (
 event_id UUID PRIMARY KEY, message_id BIGINT NOT NULL REFERENCES message(message_id) ON DELETE CASCADE,
 attempt_id UUID NOT NULL REFERENCES message_processing(attempt_id) ON DELETE CASCADE,
 event_type VARCHAR(80) NOT NULL, body TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 published_at TIMESTAMPTZ, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error VARCHAR(100)
);
CREATE INDEX processing_event_pending ON processing_event_outbox(next_attempt_at) WHERE published_at IS NULL;
CREATE TABLE operational_alert (
 alert_key VARCHAR(160) PRIMARY KEY, severity VARCHAR(20) NOT NULL, detail VARCHAR(500) NOT NULL,
 first_seen TIMESTAMPTZ NOT NULL DEFAULT now(), last_seen TIMESTAMPTZ NOT NULL DEFAULT now(), resolved_at TIMESTAMPTZ
);
CREATE TABLE cold_object_cleanup (
 object_key VARCHAR(500) PRIMARY KEY, message_id BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error VARCHAR(100)
);
CREATE FUNCTION m3_queue_cold_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.archive_key IS NOT NULL THEN
   INSERT INTO cold_object_cleanup(object_key,message_id) VALUES(OLD.archive_key,OLD.message_id) ON CONFLICT DO NOTHING;
 END IF;
 RETURN OLD;
END; $$;
CREATE TRIGGER m3_queue_cold_cleanup AFTER DELETE ON message FOR EACH ROW EXECUTE FUNCTION m3_queue_cold_cleanup();
INSERT INTO message_processing(attempt_id,message_id,recipient,required,attempt_no,status,created_at,started_at,completed_at,deadline_at,loaded_timeout_seconds,processing_timeout_seconds)
 SELECT gen_random_uuid(),message_id,'external',true,1,
 CASE WHEN status IN ('LOADED','PROCESSING','PROCESSED','PROCESSING_FAILED') THEN status ELSE 'LOADED' END,
 created_at,CASE WHEN status='PROCESSING' THEN created_at END,
 CASE WHEN status IN ('PROCESSED','PROCESSING_FAILED') THEN coalesce(processed_at,created_at) END,
 CASE WHEN status='PROCESSING' THEN created_at+interval '1 hour' WHEN status NOT IN ('PROCESSED','PROCESSING_FAILED') THEN created_at+interval '1 day' END,
 86400,3600 FROM message WHERE direction='INBOUND';
