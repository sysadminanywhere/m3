-- Preserve existing capacity settings and the current identity sequence.
INSERT INTO rule_worker_pool (name, desired_replicas, min_replicas, max_replicas,
                              auto_scale_enabled, pending_jobs_per_worker, created_at)
VALUES ('default', 1, 1, 4, FALSE, 50, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO NOTHING;

-- Rules from before the container architecture gain an initial assignment.
UPDATE rule SET worker_pool_id = (SELECT worker_pool_id FROM rule_worker_pool WHERE name = 'default')
WHERE worker_pool_id IS NULL;
