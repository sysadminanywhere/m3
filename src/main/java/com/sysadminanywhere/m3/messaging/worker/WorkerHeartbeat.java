package com.sysadminanywhere.m3.messaging.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** A heartbeat for every worker process, including outbound-only and idle workers. */
@Component
@Profile("worker")
public class WorkerHeartbeat {
    private final JdbcTemplate jdbc;
    private final UUID instanceId = UUID.randomUUID();
    private final String pool;
    private final String name;

    public WorkerHeartbeat(JdbcTemplate jdbc, @Value("${m3.worker.pool:default}") String pool,
                           @Value("${HOSTNAME:local-worker}") String name) {
        this.jdbc = jdbc; this.pool = pool;
        this.name = name.substring(0, Math.min(name.length(), 160));
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 1000,scheduler="workerLeaseScheduler")
    public void heartbeat() {
        jdbc.update("""
                INSERT INTO worker_heartbeat(instance_id, worker_pool_id, worker_name, checked_at)
                SELECT ?, worker_pool_id, ?, now() FROM rule_worker_pool WHERE name=?
                ON CONFLICT(instance_id) DO UPDATE SET checked_at=EXCLUDED.checked_at,
                    worker_pool_id=EXCLUDED.worker_pool_id, worker_name=EXCLUDED.worker_name
                """, instanceId, name, pool);
        jdbc.update("DELETE FROM worker_heartbeat WHERE checked_at < now() - interval '1 day'");
    }
}
