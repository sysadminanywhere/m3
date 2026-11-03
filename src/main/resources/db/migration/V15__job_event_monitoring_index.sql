create index job_event_monitoring_idx on community.job_event(occurred_at) where status in ('RETRY','UNCERTAIN');
