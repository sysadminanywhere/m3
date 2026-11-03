package com.sysadminanywhere.m3.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Scrape-time gauges: database values describe the shared installation, not one replica. */
@RestController
public class PrometheusMetricsController {
    private final JdbcTemplate jdbc;
    private final Path storage;
    public PrometheusMetricsController(JdbcTemplate jdbc,@Value("${M3_STORAGE_ROOT:${m3.storage.root:./data/messages}}") String storage) {
        this.jdbc=jdbc; this.storage=Path.of(storage);
    }
    @GetMapping(value="/api/v2/metrics/prometheus",produces="text/plain;version=0.0.4;charset=utf-8")
    public String metrics() {
        Map<String,Object> totals=jdbc.queryForMap("""
            select count(*) filter (where status='READY') as ready,
                   count(*) filter (where status='RETRY') as retry,
                   count(*) filter (where status='RUNNING') as running,
                   count(*) filter (where status='UNCERTAIN') as uncertain,
                   count(*) filter (where status='DELIVERED') as delivered,
                   count(*) filter (where status='DELIVERED' and completed_at >= now()-interval '1 hour') as delivered_hour,
                   coalesce(greatest(0,extract(epoch from now()-min(created_at) filter (where status in ('READY','RETRY','RUNNING','UNCERTAIN')))),0) as oldest_seconds
            from community.execution_job
            """);
        Map<String,Object> failures=jdbc.queryForMap("""
            select count(*) filter (where status='RETRY') as retry,
                   count(*) filter (where status='UNCERTAIN') as uncertain
            from community.job_event
            where occurred_at >= now()-interval '1 hour' and status in ('RETRY','UNCERTAIN')
            """);
        var out=new StringBuilder();
        out.append("# HELP m3_jobs Current retained jobs in the shared Community database.\n# TYPE m3_jobs gauge\n");
        for (String state : java.util.List.of("READY","RETRY","RUNNING","UNCERTAIN","DELIVERED"))
            out.append("m3_jobs{status=\"").append(state).append("\"} ").append(totals.get(state.toLowerCase(java.util.Locale.ROOT))).append('\n');
        out.append("# HELP m3_jobs_delivered_last_hour Retained jobs completed in the previous hour.\n# TYPE m3_jobs_delivered_last_hour gauge\n")
                .append("m3_jobs_delivered_last_hour ").append(totals.get("delivered_hour")).append('\n')
                .append("# HELP m3_job_failure_events_last_hour Retained retry or uncertain history entries in the previous hour.\n# TYPE m3_job_failure_events_last_hour gauge\n");
        for (String state : java.util.List.of("RETRY","UNCERTAIN"))
            out.append("m3_job_failure_events_last_hour{status=\"").append(state).append("\"} ").append(failures.get(state.toLowerCase(java.util.Locale.ROOT))).append('\n');
        out
                .append("# HELP m3_oldest_open_job_seconds Age of the oldest open job, or zero when none exist.\n# TYPE m3_oldest_open_job_seconds gauge\n")
                .append("m3_oldest_open_job_seconds ").append(totals.get("oldest_seconds")).append('\n');
        boolean available=false; long usable=0,total=0;
        try { var store=Files.getFileStore(storage); usable=store.getUsableSpace(); total=store.getTotalSpace(); available=true; }
        catch (java.io.IOException | SecurityException ignored) { /* Report unavailable; do not invent capacity values. */ }
        out.append("# HELP m3_storage_available Whether the configured storage path was readable by this instance.\n# TYPE m3_storage_available gauge\n")
                .append("m3_storage_available ").append(available?1:0).append('\n');
        if (available) out.append("# HELP m3_storage_usable_bytes Usable filesystem space visible to this Community instance.\n# TYPE m3_storage_usable_bytes gauge\n")
                .append("m3_storage_usable_bytes ").append(usable).append('\n')
                .append("# HELP m3_storage_total_bytes Filesystem capacity visible to this Community instance.\n# TYPE m3_storage_total_bytes gauge\n")
                .append("m3_storage_total_bytes ").append(total).append('\n');
        return out.toString();
    }
}
