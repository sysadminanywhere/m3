ALTER TABLE rule_worker_pool ADD COLUMN IF NOT EXISTS capacity_priority INTEGER NOT NULL DEFAULT 100;
CREATE TABLE m3_installation (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
    installation_id UUID NOT NULL,
    license_document TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO m3_installation(singleton,installation_id) VALUES(true,gen_random_uuid());
CREATE TABLE worker_slot (
    instance_id UUID PRIMARY KEY,
    pool_name VARCHAR(80) NOT NULL,
    worker_name VARCHAR(160) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX worker_slot_expiry ON worker_slot(expires_at);
CREATE TABLE worker_drain (
    worker_name VARCHAR(160) PRIMARY KEY,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
