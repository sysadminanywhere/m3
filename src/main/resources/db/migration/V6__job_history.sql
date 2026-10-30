create table community.job_event (
    id bigserial primary key,
    job_id uuid not null references community.execution_job(id),
    status text not null,
    attempt integer not null,
    worker_id text,
    error text,
    occurred_at timestamptz not null default now()
);
create index job_event_job_id_idx on community.job_event(job_id, id);

insert into community.job_event(job_id, status, attempt, worker_id, error)
select id, status, attempts, claimed_by, last_error from community.execution_job;

create function community.record_job_event() returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' or new.status is distinct from old.status then
        insert into community.job_event(job_id, status, attempt, worker_id, error)
        values (new.id, new.status, new.attempts, coalesce(new.claimed_by, old.claimed_by), new.last_error);
    end if;
    return new;
end;
$$;
create trigger execution_job_history after insert or update of status on community.execution_job
for each row execute function community.record_job_event();
