create table if not exists community.secret_record (
    id text primary key,
    ciphertext bytea not null,
    iv bytea not null,
    updated_at timestamptz not null default now()
);
