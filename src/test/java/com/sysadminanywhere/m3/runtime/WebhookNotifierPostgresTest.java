package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
class WebhookNotifierPostgresTest {
    @Test void retriesFailedDeliveryAndDeduplicatesAfterAcknowledgement(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var message=UUID.randomUUID(); var job=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','checksum','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'RETRY')",job,message);
        var count=new AtomicInteger(); var body=new AtomicReference<String>(); var auth=new AtomicReference<String>(); var event=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/event",exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); event.set(exchange.getRequestHeaders().getFirst("X-M3-Event-Id"));
            exchange.sendResponseHeaders(count.incrementAndGet()==1?503:204,-1); exchange.close();
        });
        server.start();
        try {
            var notifier=new WebhookNotifier(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper(),ref -> "token-value".getBytes(StandardCharsets.UTF_8),
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/event","token",1,"RETRY,UNCERTAIN");
            notifier.poll(); assertEquals("RETRY",jdbc.queryForObject("select state from community.webhook_delivery",String.class));
            var operations=new com.sysadminanywhere.m3.api.OperationsController(jdbc);
            var pending=operations.notificationDeliveries(100,0);
            assertEquals(1,pending.size()); assertEquals("webhook",pending.get(0).channel());
            assertEquals("RETRY",pending.get(0).state()); assertEquals(1,pending.get(0).attempts()); assertNull(pending.get(0).deliveredAt());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> operations.notificationDeliveries(501,0));
            assertEquals(1,count.get()); assertFalse(body.get().contains("token-value")); assertEquals("Bearer token-value",auth.get());
            var eventId=event.get(); jdbc.update("update community.webhook_delivery set next_attempt_at=now()-interval '1 second'");
            jdbc.update("update community.webhook_rate_limit set next_at=now()-interval '1 second'");
            notifier.poll(); assertEquals("DELIVERED",jdbc.queryForObject("select state from community.webhook_delivery",String.class));
            var delivered=operations.notificationDeliveries(100,0).get(0);
            assertEquals("DELIVERED",delivered.state()); assertEquals(2,delivered.attempts()); assertNotNull(delivered.deliveredAt());
            var history=operations.notificationAttempts("webhook",delivered.eventId(),100,0);
            assertEquals(2,history.size()); assertEquals("ACCEPTED",history.get(0).outcome()); assertEquals("RETRY",history.get(1).outcome());
            jdbc.update("update community.notification_attempt set finished_at=now()-interval '31 days' where channel='webhook' and attempt=1");
            new NotificationAttemptRetention(jdbc).cleanup();
            assertEquals(1,operations.notificationAttempts("webhook",delivered.eventId(),100,0).size());
            assertEquals(eventId,event.get()); assertEquals(2,count.get());
            jdbc.update("update community.webhook_rate_limit set next_at=now()-interval '1 second'"); notifier.poll(); assertEquals(2,count.get());
            assertEquals(job.toString(),new ObjectMapper().readTree(body.get()).path("jobId").asText());
        } finally { server.stop(0); }
    }
    @Test void rejectsInsecureOrCredentialBearingUrls(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds); var mapper=new ObjectMapper();
        assertThrows(IllegalArgumentException.class,() -> new WebhookNotifier(jdbc,tx,mapper,ref -> new byte[0],"http://example.com/hook","",10,"RETRY"));
        assertThrows(IllegalArgumentException.class,() -> new WebhookNotifier(jdbc,tx,mapper,ref -> new byte[0],"https://example.com/hook?token=secret","",10,"RETRY"));
        assertThrows(IllegalArgumentException.class,() -> new WebhookNotifier(jdbc,tx,mapper,ref -> new byte[0],"https://example.com/hook","",10,"BOGUS"));
    }
    @Test void concurrentReplicasClaimEventOnlyOnce(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var message=UUID.randomUUID(); var job=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','checksum','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",job,message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'RETRY')",UUID.randomUUID(),message);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1); var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/event",exchange -> { requests.incrementAndGet(); entered.countDown();
            try { release.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(204,-1); exchange.close(); }); server.start();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/event";
            var first=new WebhookNotifier(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper(),ref -> new byte[0],url,"",1,"UNCERTAIN");
            var second=new WebhookNotifier(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper(),ref -> new byte[0],url,"",1,"UNCERTAIN");
            var running=executor.submit(first::poll); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            second.poll(); assertEquals(1,requests.get()); assertEquals("SENDING",jdbc.queryForObject("select state from community.webhook_delivery",String.class));
            release.countDown(); running.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("DELIVERED",jdbc.queryForObject("select state from community.webhook_delivery",String.class));
        } finally { release.countDown(); executor.shutdownNow(); server.stop(0); }
    }
    @Test void alertsAreDeduplicatedUntilRecoveryAndQueueThresholdCanRearm(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds); var alerts=new OperationalAlerts(jdbc,tx);
        alerts.set("storage:s","STORAGE_FULL",true); alerts.set("storage:s","STORAGE_FULL",true);
        assertEquals(1,jdbc.queryForObject("select count(*) from community.operational_alert_event",Integer.class));
        alerts.set("storage:s","STORAGE_FULL",false); alerts.set("storage:s","STORAGE_FULL",true);
        assertEquals(2,jdbc.queryForObject("select count(*) from community.operational_alert_event",Integer.class));
        var message=UUID.randomUUID(); var job=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','checksum','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')",job,message);
        var count=new AtomicInteger(); var lastBody=new AtomicReference<String>(); var firstId=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/event",exchange -> { lastBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            firstId.compareAndSet(null,exchange.getRequestHeaders().getFirst("X-M3-Event-Id")); count.incrementAndGet(); exchange.sendResponseHeaders(204,-1); exchange.close(); });
        server.start();
        try {
            var notifier=new WebhookNotifier(jdbc,tx,new ObjectMapper(),ref -> new byte[0],"http://127.0.0.1:"+server.getAddress().getPort()+"/event","",1,"QUEUE_HIGH",1,alerts);
            notifier.poll(); assertEquals(1,count.get()); assertEquals("QUEUE_HIGH",new ObjectMapper().readTree(lastBody.get()).path("type").asText());
            jdbc.update("update community.webhook_rate_limit set next_at=now()-interval '1 second'"); notifier.poll(); assertEquals(1,count.get());
            jdbc.update("update community.execution_job set status='DELIVERED' where id=?",job); notifier.poll();
            jdbc.update("update community.execution_job set status='READY' where id=?",job); notifier.poll();
            assertEquals(2,count.get()); assertNotEquals(firstId.get(),new ObjectMapper().readTree(lastBody.get()).path("eventId").asText());
            assertEquals(0,jdbc.queryForObject("select count(*) from community.webhook_alert_delivery d join community.operational_alert_event e on e.id=d.event_id where e.event_type='STORAGE_FULL'",Integer.class));
            var storageNotifier=new WebhookNotifier(jdbc,tx,new ObjectMapper(),ref -> new byte[0],"http://127.0.0.1:"+server.getAddress().getPort()+"/event","",1,"STORAGE_FULL",0,alerts);
            jdbc.update("update community.webhook_rate_limit set next_at=now()-interval '1 second'"); storageNotifier.poll();
            assertEquals("STORAGE_FULL",new ObjectMapper().readTree(lastBody.get()).path("type").asText());
            assertEquals("storage:s",new ObjectMapper().readTree(lastBody.get()).path("alertKey").asText());
        } finally { server.stop(0); }
    }
}
