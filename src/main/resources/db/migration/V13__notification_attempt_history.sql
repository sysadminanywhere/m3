create table community.notification_attempt (
    id bigint generated always as identity primary key,
    channel text not null check (channel in ('webhook','email')),
    event_key text not null,
    attempt integer not null check (attempt > 0),
    outcome text not null check (outcome in ('ACCEPTED','RETRY')),
    finished_at timestamptz not null default now(),
    unique (channel,event_key,attempt)
);
create index notification_attempt_event_idx on community.notification_attempt(channel,event_key,id desc);
create index notification_attempt_finished_idx on community.notification_attempt(finished_at);
