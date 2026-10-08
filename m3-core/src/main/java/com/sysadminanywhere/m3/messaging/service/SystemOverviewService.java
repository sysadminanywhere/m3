package com.sysadminanywhere.m3.messaging.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Profile("!worker")
public class SystemOverviewService {
    private final JdbcTemplate jdbc;
    public SystemOverviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PoolStatus(long id, String name, long pending, long processing, long failed,
                             long activeWorkers, int desiredWorkers, Instant lastHeartbeat) { }
    public record Snapshot(Instant sampledAt, long pending, long processing, long failedInbound,
                           long failedOutbound, long pendingReceipts, long retryingReceipts,
                           Instant oldestPending, List<PoolStatus> pools) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot() {
        var queue = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status='PENDING') AS pending,
                       count(*) FILTER (WHERE status='PROCESSING') AS processing,
                       min(created_at) FILTER (WHERE status='PENDING') AS oldest
                FROM rule_execution_job
                """);
        var failures = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE direction='INBOUND') AS inbound,
                       count(*) FILTER (WHERE direction='OUTBOUND') AS outbound
                FROM message m WHERE status IN ('FAILED','PROCESSING_FAILED')
                   OR (direction='INBOUND' AND EXISTS(SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id AND j.status='FAILED'))
                """);
        var receipts = jdbc.queryForMap("""
                SELECT count(*) AS pending, count(*) FILTER (WHERE attempts>0) AS retrying
                FROM message_receipt_outbox WHERE published_at IS NULL
                """);
        var pools = jdbc.query("""
                WITH queue AS (
                    SELECT CASE WHEN j.status='PENDING' THEN r.worker_pool_id ELSE j.worker_pool_id END AS pool_id,
                           count(*) FILTER (WHERE j.status='PENDING') AS pending,
                           count(*) FILTER (WHERE j.status='PROCESSING') AS processing,
                           count(*) FILTER (WHERE j.status='FAILED') AS failed
                    FROM rule_execution_job j JOIN rule r ON r.rule_id=j.rule_id
                    WHERE j.status IN ('PENDING','PROCESSING','FAILED') GROUP BY 1
                ), heartbeats AS (
                    SELECT worker_pool_id, count(*) FILTER (WHERE checked_at >= now()-interval '35 seconds') AS active,
                           max(checked_at) AS last_seen FROM worker_heartbeat GROUP BY worker_pool_id
                )
                SELECT p.worker_pool_id, p.name, coalesce(q.pending,0), coalesce(q.processing,0), coalesce(q.failed,0),
                       coalesce(h.active,0), p.desired_replicas, h.last_seen
                FROM rule_worker_pool p LEFT JOIN queue q ON q.pool_id=p.worker_pool_id
                LEFT JOIN heartbeats h ON h.worker_pool_id=p.worker_pool_id ORDER BY p.name
                """, (rs, row) -> new PoolStatus(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()));
        Instant now = jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
        return new Snapshot(now, number(queue,"pending"), number(queue,"processing"), number(failures,"inbound"),
                number(failures,"outbound"), number(receipts,"pending"), number(receipts,"retrying"),
                queue.get("oldest") == null ? null : ((java.sql.Timestamp) queue.get("oldest")).toInstant(), List.copyOf(pools));
    }
    private long number(java.util.Map<String,Object> values, String key) { return ((Number) values.get(key)).longValue(); }
}
