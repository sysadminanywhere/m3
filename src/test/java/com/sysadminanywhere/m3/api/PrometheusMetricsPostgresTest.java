package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
class PrometheusMetricsPostgresTest {
    @Test void reportsSharedQueueAndLocalStorageWithoutInventingMissingCapacity(DriverManagerDataSource ds,@TempDir Path dir) {
        var jdbc=new JdbcTemplate(ds); UUID message=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','sum','file','key','RECEIVED')",message);
        UUID retryJob=UUID.randomUUID();
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status,created_at) values (?,?,'r',1,'RETRY',now()-interval '2 minutes')",retryJob,message);
        jdbc.update("insert into community.job_event(job_id,status,attempt,occurred_at) values (?,'RETRY',1,now()-interval '2 hours')",retryJob);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status,completed_at) values (?,?,'r',1,'DELIVERED',now())",UUID.randomUUID(),message);
        String metrics=new PrometheusMetricsController(jdbc,dir.toString()).metrics();
        assertTrue(metrics.contains("m3_jobs{status=\"RETRY\"} 1\n"));
        assertTrue(metrics.contains("m3_jobs_delivered_last_hour 1\n"));
        assertTrue(metrics.contains("m3_job_failure_events_last_hour{status=\"RETRY\"} 1\n"));
        assertTrue(metrics.contains("m3_job_failure_events_last_hour{status=\"UNCERTAIN\"} 0\n"));
        assertTrue(metrics.contains("m3_storage_available 1\n"));
        String missing=new PrometheusMetricsController(jdbc,dir.resolve("missing").toString()).metrics();
        assertTrue(missing.contains("m3_storage_available 0\n")); assertFalse(missing.contains("m3_storage_usable_bytes "));
    }
}
