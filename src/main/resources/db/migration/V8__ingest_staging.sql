create table community.ingest_staging (
    id uuid primary key,
    source_id text not null,
    owner_id text not null,
    created_at timestamptz not null default now()
);
create index ingest_staging_created_idx on community.ingest_staging(created_at);
