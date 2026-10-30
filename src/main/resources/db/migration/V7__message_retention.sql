alter table community.message_record add column retain_until timestamptz;
create table community.orphan_candidate (checksum text primary key, first_seen timestamptz not null default now());
create table community.message_deletion_audit (
    id bigserial primary key,
    message_id uuid not null,
    source_id text not null,
    checksum text not null,
    message_snapshot jsonb not null,
    job_history jsonb not null,
    deleted_at timestamptz not null default now()
);
