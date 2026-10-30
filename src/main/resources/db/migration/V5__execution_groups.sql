alter table community.execution_job
    add column if not exists execution_group text not null default 'default';

create index if not exists execution_job_group_ready_idx
    on community.execution_job(execution_group, status, next_attempt_at, created_at);
