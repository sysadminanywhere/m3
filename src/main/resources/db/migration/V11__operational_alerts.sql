create table community.operational_alert_state (
    alert_key text primary key,
    active boolean not null,
    updated_at timestamptz not null default now()
);
create table community.operational_alert_event (
    id uuid primary key,
    alert_key text not null,
    event_type text not null,
    occurred_at timestamptz not null default now()
);
create table community.webhook_alert_delivery (
    event_id uuid primary key references community.operational_alert_event(id) on delete cascade,
    state text not null check(state in ('READY','SENDING','RETRY','DELIVERED')),
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    lease_until timestamptz,
    claim_token uuid,
    delivered_at timestamptz
);
create index webhook_alert_delivery_ready_idx on community.webhook_alert_delivery(state,next_attempt_at,event_id);
