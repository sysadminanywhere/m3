create index if not exists execution_job_expired_lease_idx
    on community.execution_job(status, lease_until)
    where status = 'RUNNING';
