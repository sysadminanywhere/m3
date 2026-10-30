create table if not exists community.source_lease (
    source_id text primary key,
    owner_id text not null,
    lease_until timestamptz not null,
    updated_at timestamptz not null default now()
);

create index if not exists source_lease_expiry_idx
    on community.source_lease(lease_until);
