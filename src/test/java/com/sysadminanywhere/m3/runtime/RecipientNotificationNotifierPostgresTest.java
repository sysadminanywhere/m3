package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
class RecipientNotificationNotifierPostgresTest {
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_SMTP_UNTRUSTED_PORT",matches="[0-9]+")
    void emailRecipientRejectsUntrustedCertificate(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); var tx=new DataSourceTransactionManager(ds);
        var settings=mapper.createObjectNode().put("host","localhost").put("port",Integer.parseInt(System.getenv("M3_TEST_SMTP_UNTRUSTED_PORT")))
                .put("from","m3@example.test").put("to","ops@example.test");
        var id=new NotificationRecipientStore(jdbc).create(new NotificationRecipientStore.Settings("TLS email","email",true,List.of("UNCERTAIN"),0,1,settings));
        UUID message=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','sum','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",UUID.randomUUID(),message);
        var notifier=new RecipientNotificationNotifier(jdbc,tx,mapper,ref -> new byte[0],new OperationalAlerts(jdbc,tx));
        notifier.poll(); assertEquals("RETRY",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,id)); notifier.shutdown();
    }
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_SMTP_TLS_PORT",matches="[0-9]+")
    void emailRecipientRequiresAndAcceptsTrustedStartTls(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); var tx=new DataSourceTransactionManager(ds);
        var settings=mapper.createObjectNode().put("host","localhost").put("port",Integer.parseInt(System.getenv("M3_TEST_SMTP_TLS_PORT")))
                .put("from","m3@example.test").put("to","ops@example.test");
        var id=new NotificationRecipientStore(jdbc).create(new NotificationRecipientStore.Settings("TLS email","email",true,List.of("UNCERTAIN"),0,1,settings));
        UUID message=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','sum','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",UUID.randomUUID(),message);
        var notifier=new RecipientNotificationNotifier(jdbc,tx,mapper,ref -> new byte[0],new OperationalAlerts(jdbc,tx));
        notifier.poll(); assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,id)); notifier.shutdown();
    }
    @Test void emailRecipientRetriesUntilSmtpAccepts(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); var tx=new DataSourceTransactionManager(ds);
        try (var smtp=new EmailNotifierPostgresTest.TestSmtp()) {
            var settings=mapper.createObjectNode().put("host","127.0.0.1").put("port",smtp.port()).put("from","m3@example.test")
                    .put("to","ops@example.test").put("username","m3user").put("passwordRef","smtp-ref").put("allowPlaintextLoopback",true);
            var id=new NotificationRecipientStore(jdbc).create(new NotificationRecipientStore.Settings("Email","email",true,List.of("UNCERTAIN"),0,1,settings));
            UUID message=UUID.randomUUID();
            jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','sum','file','key','RECEIVED')",message);
            jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",UUID.randomUUID(),message);
            var notifier=new RecipientNotificationNotifier(jdbc,tx,mapper,ref -> "test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8),new OperationalAlerts(jdbc,tx));
            notifier.poll(); assertEquals("RETRY",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,id));
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second' where id=?",id);
            jdbc.update("update community.notification_recipient_job_delivery set next_attempt_at=now()-interval '1 second' where recipient_id=?",id);
            notifier.poll(); assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,id));
            assertEquals(2,smtp.received.get()); assertTrue(smtp.authenticated.get());
            assertFalse(smtp.message.get().contains("test-password"));
            assertEquals(2,jdbc.queryForObject("select count(*) from community.notification_attempt where recipient_id=? and channel='email'",Integer.class,id));
            notifier.shutdown();
        }
    }
    @Test void concurrentWorkersDoNotClaimSameDelivery(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); var tx=new DataSourceTransactionManager(ds);
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1); var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/event",exchange -> { count.incrementAndGet(); entered.countDown();
            try { release.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(204,-1); exchange.close(); }); server.start();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var store=new NotificationRecipientStore(jdbc);
            var id=store.create(new NotificationRecipientStore.Settings("Test","webhook",true,List.of("RETRY"),0,1,mapper.createObjectNode().put("url","http://127.0.0.1:"+server.getAddress().getPort()+"/event")));
            UUID message=UUID.randomUUID(),job=UUID.randomUUID();
            jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','sum','file','key','RECEIVED')",message);
            jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'RETRY')",job,message);
            var alerts=new OperationalAlerts(jdbc,tx);
            var first=new RecipientNotificationNotifier(jdbc,tx,mapper,ref -> new byte[0],alerts);
            var second=new RecipientNotificationNotifier(jdbc,tx,mapper,ref -> new byte[0],alerts);
            var task=pool.submit(first::poll); assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second' where id=?",id);
            second.poll(); assertEquals(1,count.get()); release.countDown(); task.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,id));
            first.shutdown(); second.shutdown();
        } finally { release.countDown(); pool.shutdownNow(); server.stop(0); }
    }
    @Test void recipientsRetryIndependently(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper(); var store=new NotificationRecipientStore(jdbc);
        var healthy=new AtomicInteger(); var failing=new AtomicInteger(); var restored=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/healthy",exchange -> { healthy.incrementAndGet(); exchange.sendResponseHeaders(204,-1); exchange.close(); });
        server.createContext("/failing",exchange -> { failing.incrementAndGet(); exchange.sendResponseHeaders(restored.get()?204:503,-1); exchange.close(); });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var a=store.create(new NotificationRecipientStore.Settings("Healthy","webhook",true,List.of("RETRY","STORAGE_FULL"),0,1,mapper.createObjectNode().put("url",base+"/healthy")));
            var b=store.create(new NotificationRecipientStore.Settings("Failing","webhook",true,List.of("RETRY"),0,1,mapper.createObjectNode().put("url",base+"/failing")));
            UUID message=UUID.randomUUID(),job=UUID.randomUUID();
            jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','sum','file','key','RECEIVED')",message);
            jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'RETRY')",job,message);
            var tx=new DataSourceTransactionManager(ds);
            var notifier=new RecipientNotificationNotifier(jdbc,tx,mapper,ref -> new byte[0],new OperationalAlerts(jdbc,tx));
            notifier.poll(); assertEquals(1,healthy.get()); assertEquals(1,failing.get());
            assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,a));
            assertEquals("RETRY",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,b));
            restored.set(true);
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second'");
            jdbc.update("update community.notification_recipient_job_delivery set next_attempt_at=now()-interval '1 second'");
            notifier.poll(); assertEquals(1,healthy.get()); assertEquals(2,failing.get());
            assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_job_delivery where recipient_id=?",String.class,b));
            assertEquals(2,jdbc.queryForObject("select count(*) from community.notification_attempt where recipient_id=?",Integer.class,b));
            var operations=new com.sysadminanywhere.m3.api.OperationsController(jdbc);
            var delivery=operations.notificationDeliveries(100,0).stream().filter(item -> b.equals(item.recipientId())).findFirst().orElseThrow();
            assertEquals("DELIVERED",delivery.state());
            assertEquals(2,operations.notificationAttempts("webhook",delivery.eventId(),b,100,0).size());
            assertTrue(operations.notificationAttempts("webhook",delivery.eventId(),null,100,0).isEmpty());
            new OperationalAlerts(jdbc,tx).set("storage:test","STORAGE_FULL",true);
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second'");
            notifier.poll(); assertEquals(2,healthy.get()); assertEquals(2,failing.get());
            assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_alert_delivery where recipient_id=?",String.class,a));
            assertTrue(store.update(a,1,new NotificationRecipientStore.Settings("Healthy","webhook",true,List.of("RETRY","QUEUE_HIGH"),1,1,mapper.createObjectNode().put("url",base+"/healthy"))));
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second'");
            notifier.poll(); assertEquals(3,healthy.get());
            assertEquals(1,jdbc.queryForObject("select count(*) from community.operational_alert_event where alert_key=? and event_type='QUEUE_HIGH'",Integer.class,"queue:recipient:"+a));
            jdbc.update("update community.execution_job set status='DELIVERED' where id=?",job); notifier.poll();
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second'");
            jdbc.update("update community.execution_job set status='RETRY' where id=?",job); notifier.poll();
            assertEquals(2,jdbc.queryForObject("select count(*) from community.operational_alert_event where alert_key=? and event_type='QUEUE_HIGH'",Integer.class,"queue:recipient:"+a));
            UUID testEvent=store.requestTest(a); assertNotNull(testEvent);
            jdbc.update("update community.notification_recipient set next_at=now()-interval '1 second' where id=?",a);
            notifier.poll();
            assertEquals("DELIVERED",jdbc.queryForObject("select state from community.notification_recipient_test_delivery where recipient_id=? and event_id=?",String.class,a,testEvent));
            assertTrue(operations.notificationDeliveries(100,0).stream().anyMatch(item -> item.eventId().equals("test:"+testEvent) && item.recipientId().equals(a)));
            assertEquals("ACCEPTED",operations.notificationAttempts("webhook","test:"+testEvent,a,100,0).get(0).outcome());
            notifier.shutdown();
        } finally { server.stop(0); }
    }
}
