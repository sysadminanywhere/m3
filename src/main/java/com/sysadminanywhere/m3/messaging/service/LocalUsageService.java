package com.sysadminanywhere.m3.messaging.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Local aggregates over retained records; no data leaves the installation. */
@Service
@Profile("!worker")
public class LocalUsageService {
    public record Count(String type, long count) { }
    public record Summary(long activeDays, long inbound, long outbound, long sent, long failed,
                          long enabledRules, List<Count> channels, List<Count> actions) { }
    private final JdbcTemplate jdbc;
    public LocalUsageService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    public Summary summary() {
        var totals = jdbc.queryForMap("""
                SELECT count(DISTINCT (created_at AT TIME ZONE 'UTC')::date) AS days,
                  count(*) FILTER (WHERE direction='INBOUND') AS inbound,
                  count(*) FILTER (WHERE direction='OUTBOUND') AS outbound,
                  count(*) FILTER (WHERE direction='OUTBOUND' AND status='SENT') AS sent,
                  count(*) FILTER (WHERE direction='OUTBOUND' AND status='FAILED') AS failed
                FROM message WHERE created_at >= now()-interval '30 days' AND created_at <= now()
                """);
        long rules = jdbc.queryForObject("SELECT count(*) FROM rule WHERE enabled=true", Long.class);
        var channels = jdbc.query("SELECT channel_type,count(*) FROM channel_settings WHERE enabled=true GROUP BY channel_type ORDER BY channel_type",
                (rs, row) -> new Count(rs.getString(1),rs.getLong(2)));
        var actions = jdbc.query("SELECT a.action_type,count(*) FROM rule_action a JOIN rule r USING(rule_id) WHERE r.enabled=true GROUP BY a.action_type ORDER BY a.action_type",
                (rs, row) -> new Count(rs.getString(1),rs.getLong(2)));
        return new Summary(number(totals,"days"),number(totals,"inbound"),number(totals,"outbound"),
                number(totals,"sent"),number(totals,"failed"),rules,List.copyOf(channels),List.copyOf(actions));
    }
    private static long number(java.util.Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
}
