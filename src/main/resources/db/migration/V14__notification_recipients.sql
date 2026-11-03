create table community.notification_recipient (
    id uuid primary key,
    revision bigint not null default 1 check (revision > 0),
    name text not null check (length(name) between 1 and 120),
    channel text not null check (channel in ('webhook','email')),
    enabled boolean not null default true,
    deleted_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    events text[] not null,
    queue_threshold bigint not null default 0 check (queue_threshold >= 0),
    minimum_interval_seconds integer not null default 10 check (minimum_interval_seconds between 1 and 3600),
    next_at timestamptz not null default now(),
    settings jsonb not null
);
create table community.notification_recipient_job_delivery (
    recipient_id uuid not null references community.notification_recipient(id),
    event_id bigint not null references community.job_event(id) on delete cascade,
    state text not null check (state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz,
    primary key (recipient_id,event_id)
);
create index notification_recipient_job_ready_idx on community.notification_recipient_job_delivery(state,next_attempt_at,recipient_id,event_id);
create table community.notification_recipient_alert_delivery (
    recipient_id uuid not null references community.notification_recipient(id),
    event_id uuid not null references community.operational_alert_event(id) on delete cascade,
    state text not null check (state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz,
    primary key (recipient_id,event_id)
);
create index notification_recipient_alert_ready_idx on community.notification_recipient_alert_delivery(state,next_attempt_at,recipient_id,event_id);
create table community.notification_recipient_test_delivery (
    recipient_id uuid not null references community.notification_recipient(id),
    event_id uuid not null,
    created_at timestamptz not null default now(),
    state text not null check (state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz,
    primary key (recipient_id,event_id)
);
create index notification_recipient_test_ready_idx on community.notification_recipient_test_delivery(state,next_attempt_at,recipient_id,event_id);
alter table community.notification_attempt add column recipient_id uuid references community.notification_recipient(id);
alter table community.notification_attempt drop constraint notification_attempt_channel_event_key_attempt_key;
alter table community.notification_attempt add constraint notification_attempt_recipient_unique unique nulls not distinct (channel,recipient_id,event_key,attempt);
