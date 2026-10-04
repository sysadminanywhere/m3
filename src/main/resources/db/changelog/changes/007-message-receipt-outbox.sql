CREATE TABLE message_receipt_outbox (
    event_id UUID PRIMARY KEY,
    message_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at TIMESTAMP(6) WITH TIME ZONE,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000)
);
CREATE INDEX message_receipt_outbox_pending ON message_receipt_outbox(next_attempt_at, created_at)
    WHERE published_at IS NULL;
