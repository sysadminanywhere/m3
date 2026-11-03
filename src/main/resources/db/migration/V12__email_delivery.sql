create table community.email_job_delivery (
    event_id bigint primary key references community.job_event(id) on delete cascade,
    state text not null check(state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz
);
create index email_job_delivery_ready_idx on community.email_job_delivery(state,next_attempt_at,event_id);
create table community.email_alert_delivery (
    event_id uuid primary key references community.operational_alert_event(id) on delete cascade,
    state text not null check(state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz
);
create index email_alert_delivery_ready_idx on community.email_alert_delivery(state,next_attempt_at,event_id);
create table community.email_rate_limit (
    singleton boolean primary key default true check(singleton),
    next_at timestamptz not null default now()
);
insert into community.email_rate_limit(singleton) values(true);
