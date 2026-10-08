CREATE TABLE worker_heartbeat (
    instance_id UUID PRIMARY KEY,
    worker_pool_id BIGINT NOT NULL REFERENCES rule_worker_pool(worker_pool_id) ON DELETE CASCADE,
    worker_name VARCHAR(160) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX worker_heartbeat_pool_time ON worker_heartbeat(worker_pool_id, checked_at);
CREATE INDEX worker_heartbeat_time ON worker_heartbeat(checked_at);
