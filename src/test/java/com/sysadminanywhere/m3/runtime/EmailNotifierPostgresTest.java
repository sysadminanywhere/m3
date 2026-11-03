package com.sysadminanywhere.m3.runtime;

import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.net.ServerSocket;
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
class EmailNotifierPostgresTest {
    @Test void retriesSmtpFailureAndKeepsDeliveryIndependent(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds); var message=UUID.randomUUID(); var job=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','hash','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",job,message);
        try (var smtp=new TestSmtp()) {
            var notifier=new EmailNotifier(jdbc,tx,ref -> new byte[0],new OperationalAlerts(jdbc,tx),"127.0.0.1",smtp.port(),"","","m3@example.test","ops@example.test",true,1,"UNCERTAIN",0);
            notifier.poll(); assertEquals("RETRY",jdbc.queryForObject("select state from community.email_job_delivery",String.class));
            var deliveries=new com.sysadminanywhere.m3.api.OperationsController(jdbc);
            assertEquals("email",deliveries.notificationDeliveries(100,0).get(0).channel());
            assertEquals("RETRY",deliveries.notificationDeliveries(100,0).get(0).state());
            assertEquals(1,smtp.received.get()); assertFalse(smtp.message.get().contains("key"));
            jdbc.update("update community.email_job_delivery set next_attempt_at=now()-interval '1 second'"); jdbc.update("update community.email_rate_limit set next_at=now()-interval '1 second'");
            notifier.poll(); assertEquals("DELIVERED",jdbc.queryForObject("select state from community.email_job_delivery",String.class));
            assertEquals(2,deliveries.notificationDeliveries(100,0).get(0).attempts());
            assertNotNull(deliveries.notificationDeliveries(100,0).get(0).deliveredAt());
            var history=deliveries.notificationAttempts("email",deliveries.notificationDeliveries(100,0).get(0).eventId(),100,0);
            assertEquals(2,history.size()); assertEquals("ACCEPTED",history.get(0).outcome()); assertEquals("RETRY",history.get(1).outcome());
            assertEquals(2,smtp.received.get()); assertTrue(smtp.message.get().contains("X-M3-Event-Id:"));
            assertEquals(0,jdbc.queryForObject("select count(*) from community.webhook_delivery",Integer.class));
            jdbc.update("update community.email_rate_limit set next_at=now()-interval '1 second'"); notifier.poll(); assertEquals(2,smtp.received.get());
            notifier.shutdown();
        }
    }
    @Test void plaintextIsRestrictedToLoopback(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds);
        assertThrows(IllegalArgumentException.class,() -> new EmailNotifier(jdbc,tx,ref -> new byte[0],new OperationalAlerts(jdbc,tx),"example.com",25,"","","m3@example.test","ops@example.test",true,1,"UNCERTAIN",0));
    }
    @Test void smtpAuthenticationUsesSecretReference(DriverManagerDataSource ds) throws Exception {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds); var message=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','hash','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",UUID.randomUUID(),message);
        try (var smtp=new TestSmtp()) {
            smtp.received.set(1); byte[] password="test-password".getBytes(StandardCharsets.UTF_8);
            var notifier=new EmailNotifier(jdbc,tx,ref -> password,new OperationalAlerts(jdbc,tx),"127.0.0.1",smtp.port(),"m3user","credential","m3@example.test","ops@example.test",true,1,"UNCERTAIN",0);
            notifier.poll(); assertEquals("DELIVERED",jdbc.queryForObject("select state from community.email_job_delivery",String.class));
            assertTrue(smtp.authenticated.get()); assertArrayEquals(new byte[password.length],password); notifier.shutdown();
        }
    }
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_SMTP_TLS_PORT",matches="[0-9]+")
    void sendsThroughRealStartTlsServerAndRejectsPlaintext(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds); var message=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','hash','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",UUID.randomUUID(),message);
        int port=Integer.parseInt(System.getenv("M3_TEST_SMTP_TLS_PORT")); var alerts=new OperationalAlerts(jdbc,tx);
        var plain=new EmailNotifier(jdbc,tx,ref -> new byte[0],alerts,"127.0.0.1",port,"","","m3@example.test","ops@example.test",true,1,"UNCERTAIN",0);
        plain.poll(); assertEquals("RETRY",jdbc.queryForObject("select state from community.email_job_delivery",String.class)); plain.shutdown();
        jdbc.update("update community.email_job_delivery set next_attempt_at=now()-interval '1 second'"); jdbc.update("update community.email_rate_limit set next_at=now()-interval '1 second'");
        var secure=new EmailNotifier(jdbc,tx,ref -> new byte[0],alerts,"127.0.0.1",port,"","","m3@example.test","ops@example.test",false,1,"UNCERTAIN",0);
        secure.poll(); assertEquals("DELIVERED",jdbc.queryForObject("select state from community.email_job_delivery",String.class)); secure.shutdown();
    }
    @Test @EnabledIfEnvironmentVariable(named="M3_TEST_SMTP_UNTRUSTED_PORT",matches="[0-9]+")
    void rejectsUntrustedStartTlsCertificate(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds); var message=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','hash','file','key','RECEIVED')",message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'UNCERTAIN')",UUID.randomUUID(),message);
        var notifier=new EmailNotifier(jdbc,tx,ref -> new byte[0],new OperationalAlerts(jdbc,tx),"127.0.0.1",Integer.parseInt(System.getenv("M3_TEST_SMTP_UNTRUSTED_PORT")),"","","m3@example.test","ops@example.test",false,1,"UNCERTAIN",0);
        notifier.poll(); assertEquals("RETRY",jdbc.queryForObject("select state from community.email_job_delivery",String.class)); notifier.shutdown();
    }
    static final class TestSmtp implements AutoCloseable {
        final ServerSocket socket; final Thread thread; final AtomicInteger received=new AtomicInteger(); final AtomicReference<String> message=new AtomicReference<>(""); final java.util.concurrent.atomic.AtomicBoolean authenticated=new java.util.concurrent.atomic.AtomicBoolean();
        TestSmtp() throws Exception {
            socket=new ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"));
            thread=new Thread(() -> { while (!socket.isClosed()) try (var connection=socket.accept()) {
                connection.setSoTimeout(15000); var in=new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));
                var out=new java.io.BufferedWriter(new java.io.OutputStreamWriter(connection.getOutputStream(),StandardCharsets.US_ASCII));
                reply(out,"220 localhost test"); String line;
                while ((line=in.readLine())!=null) {
                    if (line.startsWith("EHLO")) { reply(out,"250-localhost"); reply(out,"250 AUTH PLAIN LOGIN"); }
                    else if (line.startsWith("HELO")) reply(out,"250 localhost");
                    else if (line.startsWith("AUTH PLAIN")) {
                        String token=line.length()>11?line.substring(11).trim():""; if (token.isEmpty()) { reply(out,"334 "); token=in.readLine(); }
                        String value=new String(java.util.Base64.getDecoder().decode(token),StandardCharsets.UTF_8);
                        boolean valid=value.endsWith("\0m3user\0test-password"); authenticated.set(valid); reply(out,valid?"235 authenticated":"535 invalid");
                    } else if (line.equals("AUTH LOGIN")) {
                        reply(out,"334 VXNlcm5hbWU6"); String login=new String(java.util.Base64.getDecoder().decode(in.readLine()),StandardCharsets.UTF_8);
                        reply(out,"334 UGFzc3dvcmQ6"); String password=new String(java.util.Base64.getDecoder().decode(in.readLine()),StandardCharsets.UTF_8);
                        boolean valid=login.equals("m3user") && password.equals("test-password"); authenticated.set(valid); reply(out,valid?"235 authenticated":"535 invalid");
                    }
                    else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") || line.startsWith("RSET")) reply(out,"250 OK");
                    else if (line.equals("DATA")) {
                        reply(out,"354 continue"); var text=new StringBuilder(); while ((line=in.readLine())!=null && !line.equals(".")) text.append(line).append('\n');
                        message.set(text.toString()); reply(out,received.incrementAndGet()==1?"451 temporary failure":"250 accepted");
                    } else if (line.equals("QUIT")) { reply(out,"221 bye"); break; } else reply(out,"250 OK");
                }
            } catch (java.io.IOException failure) { if (!socket.isClosed()) throw new RuntimeException(failure); } },"test-smtp");
            thread.setDaemon(true); thread.start();
        }
        int port() { return socket.getLocalPort(); }
        static void reply(java.io.BufferedWriter out,String line) throws java.io.IOException { out.write(line+"\r\n"); out.flush(); }
        public void close() throws Exception { socket.close(); thread.join(5000); }
    }
}
