-- Acknowledged deliveries survive source/application restarts. Keep receipts after message deletion.
CREATE TABLE source_delivery_receipt (
    channel_id BIGINT NOT NULL,
    delivery_key VARCHAR(64) NOT NULL,
    message_id BIGINT NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, delivery_key)
);
