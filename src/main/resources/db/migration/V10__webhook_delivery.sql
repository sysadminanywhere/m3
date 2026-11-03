create table community.webhook_delivery (
    event_id bigint primary key references community.job_event(id) on delete cascade,
    state text not null check (state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz
);
create index webhook_delivery_ready_idx on community.webhook_delivery(state,next_attempt_at,event_id);
create table community.webhook_rate_limit (
    singleton boolean primary key default true check(singleton),
    next_at timestamptz not null default now()
);
insert into community.webhook_rate_limit(singleton) values(true);
