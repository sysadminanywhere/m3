ALTER TABLE message ALTER COLUMN source_system TYPE VARCHAR(200);
ALTER TABLE message ALTER COLUMN target_system TYPE VARCHAR(200);
ALTER TABLE rule_execution_job ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE rule_execution_job ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE rule_execution_job ADD COLUMN IF NOT EXISTS claim_token UUID;
CREATE TABLE outbound_message_request (
    rule_id BIGINT NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    message_id BIGINT NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, request_key)
);
