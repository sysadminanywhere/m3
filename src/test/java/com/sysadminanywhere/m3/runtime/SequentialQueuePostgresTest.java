package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL", matches=".+")
class SequentialQueuePostgresTest {
    @Test void concurrentWorkersRespectSequentialAndStop(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds);
        var manager = new DataSourceTransactionManager(ds);
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", "{\"rules\":[{\"id\":\"r\",\"processingMode\":\"SEQUENTIAL\",\"onError\":\"STOP\"}]}");
        UUID message = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?, 's','x','x','x','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status,execution_group) values (?,?,'r',1,'READY','default'),(?,?,'r',1,'READY','default')",UUID.randomUUID(),message,UUID.randomUUID(),message);
        var first = new DirectoryDeliveryScheduler(jdbc,new ObjectMapper(),manager,"one","default");
        var second = new DirectoryDeliveryScheduler(jdbc,new ObjectMapper(),manager,"two","default");
        Method claim = DirectoryDeliveryScheduler.class.getDeclaredMethod("claim", int.class); claim.setAccessible(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return ((java.util.List<?>)claim.invoke(first,1)).size(); });
            var b = pool.submit(() -> { start.await(); return ((java.util.List<?>)claim.invoke(second,1)).size(); });
            start.countDown(); assertEquals(1, a.get()+b.get());
        }
        jdbc.update("update community.execution_job set status='UNCERTAIN' where status='RUNNING'");
        assertEquals(0, ((java.util.List<?>)claim.invoke(second,1)).size());
        jdbc.update("update community.execution_job set status='DELIVERED' where status='UNCERTAIN'");
        assertEquals(1, ((java.util.List<?>)claim.invoke(second,1)).size());
        assertEquals(2, jdbc.queryForObject("select count(*) from community.job_event where status='READY'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from community.job_event where status='RUNNING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from community.job_event where status='UNCERTAIN'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from community.job_event where status='DELIVERED'", Integer.class));
        jdbc.update("update community.execution_job set status='RETRY', next_attempt_at=now() + interval '1 hour' where status='RUNNING'");
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status,execution_group) values (?,?,'r',1,'READY','default')",UUID.randomUUID(),message);
        assertEquals(0, ((java.util.List<?>)claim.invoke(first,1)).size(), "STOP must block later jobs while a failed job waits for retry");
        jdbc.update("update community.config_bundle set document_json=jsonb_set(document_json, '{rules,0,onError}', '\"CONTINUE\"') where version=1");
        assertEquals(1, ((java.util.List<?>)claim.invoke(first,1)).size(), "CONTINUE permits a later job while retry waits");
    }
}
