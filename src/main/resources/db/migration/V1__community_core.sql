create schema if not exists community;
create table if not exists community.installation (id uuid primary key, name text not null, execution_group text not null, created_at timestamptz not null default now());
create table if not exists community.config_bundle (version integer primary key, document_json jsonb not null, published_at timestamptz not null default now());
create table if not exists community.message_record (id uuid primary key, source_id text not null, checksum text not null, original_name text not null, storage_key text not null, received_at timestamptz not null default now(), status text not null);
create unique index if not exists message_record_source_checksum_uq on community.message_record(source_id, checksum);
create table if not exists community.execution_job (id uuid primary key, message_id uuid not null references community.message_record(id), rule_id text not null, rule_version integer not null, status text not null, attempts integer not null default 0, next_attempt_at timestamptz not null default now(), claimed_by text, lease_until timestamptz, last_error text, completed_at timestamptz, created_at timestamptz not null default now());
create index if not exists execution_job_ready_idx on community.execution_job(status, next_attempt_at, created_at);
