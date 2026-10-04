package com.sysadminanywhere.m3.messaging.source;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SourceHealth {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final String workerId;
    private final boolean worker;
    public SourceHealth(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.core.env.Environment environment) {
        this.jdbc = jdbc;
        this.workerId = environment.getProperty("HOSTNAME", "local-" + java.util.UUID.randomUUID());
        this.worker = environment.matchesProfiles("worker");
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void heartbeat() {
        if (!worker) return;
        states.forEach((id, state) -> {
            jdbc.update("""
                INSERT INTO rule_source_health(rule_id,worker_id,status,checked_at,error_message)
                SELECT rule_id,?,?,now(),? FROM rule WHERE rule_id=?
                ON CONFLICT(rule_id,worker_id) DO UPDATE SET status=EXCLUDED.status,
                checked_at=EXCLUDED.checked_at,error_message=EXCLUDED.error_message
                """, workerId, state.status(), state.error(), id);
        });
    }
    public java.util.List<WorkerState> workers(long ruleId) {
        return jdbc.query("""
            SELECT worker_id,CASE WHEN checked_at < now()-interval '35 seconds' THEN 'STALE' ELSE status END,
            checked_at,error_message FROM rule_source_health WHERE rule_id=? ORDER BY worker_id
            """, (rs,row) -> new WorkerState(rs.getString(1), new State(rs.getString(2),rs.getTimestamp(3).toInstant(),rs.getString(4))), ruleId);
    }
    public record WorkerState(String workerId, State state) { }
    public record State(String status, Instant checkedAt, String error) { }
    private final ConcurrentHashMap<Long, State> states = new ConcurrentHashMap<>();
    public void starting(long id) { states.put(id, new State("STARTING", Instant.now(), null)); }
    public void success(long id) { states.put(id, new State("RUNNING", Instant.now(), null)); }
    public void failure(long id, Exception error) {
        String detail = error.getClass().getSimpleName();
        if (error instanceof IllegalArgumentException && error.getMessage() != null) {
            detail += ": " + error.getMessage().substring(0, Math.min(error.getMessage().length(), 200));
        }
        states.put(id, new State("RETRYING", Instant.now(), detail));
    }
    public void stopped(long id) {
        states.remove(id);
        if (worker) jdbc.update("DELETE FROM rule_source_health WHERE rule_id=? AND worker_id=?", id, workerId);
    }
    public State get(long id) {
        if (worker) return states.getOrDefault(id, new State("STOPPED", null, null));
        var values = workers(id);
        return values.stream().map(WorkerState::state)
                .filter(state -> !"RUNNING".equals(state.status()) && !"STALE".equals(state.status())).findFirst()
                .orElseGet(() -> values.isEmpty() ? new State("NO_WORKER", null,
                        "No receiver heartbeat. Start a worker for this rule's pool; the UI alone does not load files. Check Administration > Workers.")
                        : values.stream().map(WorkerState::state).filter(state -> "RUNNING".equals(state.status())).findFirst().orElse(values.getFirst().state()));
    }
}
