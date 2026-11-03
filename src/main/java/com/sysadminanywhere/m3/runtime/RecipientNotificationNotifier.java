package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-recipient durable notification queue with independent retries and rate limits. */
@Component
public class RecipientNotificationNotifier {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final RuntimeSecretResolver secrets;
    private final OperationalAlerts alerts;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task -> { var thread=new Thread(task,"m3-recipient-notifications"); thread.setDaemon(true); return thread; });
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    public RecipientNotificationNotifier(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper,@org.springframework.context.annotation.Lazy RuntimeSecretResolver secrets,@org.springframework.context.annotation.Lazy OperationalAlerts alerts) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.mapper=mapper; this.secrets=secrets; this.alerts=alerts;
    }
    @Scheduled(fixedDelayString="${m3.recipient-notifications.poll-ms:10000}") public void schedule() {
        if (!running.compareAndSet(false,true)) return;
        try { worker.execute(() -> { try { poll(); } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RecipientNotificationNotifier.class).warn("Recipient notification processing failed; pending events will be retried");
        } finally { running.set(false); } }); } catch (java.util.concurrent.RejectedExecutionException stopped) { running.set(false); }
    }
    @jakarta.annotation.PreDestroy public void shutdown() { worker.shutdownNow(); }
    public void poll() {
        var recipients=jdbc.query("select id from community.notification_recipient where enabled and deleted_at is null order by id",(rs,row) -> rs.getObject(1,UUID.class));
        for (UUID recipient:recipients) {
            var thresholds=jdbc.query("select queue_threshold from community.notification_recipient where id=? and enabled and deleted_at is null and 'QUEUE_HIGH'=any(events)",
                    (rs,row) -> rs.getLong(1),recipient);
            if (!thresholds.isEmpty() && thresholds.get(0)>0) {
                Long queued=jdbc.queryForObject("select count(*) from community.execution_job where status in ('READY','RETRY')",Long.class);
                alerts.set("queue:recipient:"+recipient,"QUEUE_HIGH",queued!=null && queued>=thresholds.get(0));
            } else alerts.set("queue:recipient:"+recipient,"QUEUE_HIGH",false);
            Claim claim=tx.execute(status -> claim(recipient));
            if (claim==null) continue;
            boolean accepted=false;
            try {
                var settings=mapper.readTree(claim.settings()); NotificationRecipientSettings.validate(claim.channel(),settings);
                if ("email".equals(claim.channel())) {
                    sendEmail(claim,settings); accepted=true;
                } else {
                var payload=mapper.createObjectNode().put("eventId",claim.eventKey()).put("occurredAt",claim.occurredAt());
                if (claim.testId()!=null) payload.put("type","TEST");
                else if (claim.alertId()==null) payload.put("jobId",claim.jobId().toString()).put("status",claim.type()).put("attempt",claim.jobAttempt());
                else payload.put("type",claim.type()).put("alertKey",claim.reference());
                var request=HttpRequest.newBuilder(URI.create(settings.path("url").asText())).timeout(Duration.ofSeconds(10))
                        .header("Content-Type","application/json").header("X-M3-Event-Id",claim.eventKey())
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString(),StandardCharsets.UTF_8));
                String ref=settings.path("tokenRef").asText("");
                if (!ref.isBlank()) {
                    byte[] token=secrets.resolve(ref);
                    try { String value=new String(token,StandardCharsets.UTF_8);
                        if (value.isBlank() || value.chars().anyMatch(c -> c<33 || c>126)) throw new IllegalStateException("Invalid token");
                        request.header("Authorization","Bearer "+value);
                    } finally { Arrays.fill(token,(byte)0); }
                }
                int code=http.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode(); accepted=code>=200 && code<300;
                }
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (Exception failure) { /* Never log addresses, response bodies or secrets. */ }
            boolean delivered=accepted;
            tx.executeWithoutResult(status -> finish(claim,delivered));
        }
    }
    private void sendEmail(Claim claim,com.fasterxml.jackson.databind.JsonNode settings) throws Exception {
        var properties=new java.util.Properties();
        properties.setProperty("mail.smtp.connectiontimeout","5000"); properties.setProperty("mail.smtp.timeout","10000"); properties.setProperty("mail.smtp.writetimeout","10000");
        if (!settings.path("allowPlaintextLoopback").asBoolean(false)) {
            properties.setProperty("mail.smtp.starttls.enable","true"); properties.setProperty("mail.smtp.starttls.required","true"); properties.setProperty("mail.smtp.ssl.checkserveridentity","true");
        }
        var session=jakarta.mail.Session.getInstance(properties);
        var message=new jakarta.mail.internet.MimeMessage(session);
        message.setFrom(new jakarta.mail.internet.InternetAddress(settings.path("from").asText(),true));
        message.setRecipient(jakarta.mail.Message.RecipientType.TO,new jakarta.mail.internet.InternetAddress(settings.path("to").asText(),true));
        message.setSubject("M3: "+claim.type(),"UTF-8");
        message.setText("Event: "+claim.eventKey()+"\nType: "+claim.type()+"\nReference: "+(claim.testId()!=null?"test":claim.alertId()==null?claim.jobId():claim.reference())+"\nOccurred: "+claim.occurredAt(),"UTF-8");
        message.setHeader("X-M3-Event-Id",claim.eventKey()); message.saveChanges();
        try (var transport=session.getTransport("smtp")) {
            String username=settings.path("username").asText("");
            if (username.isBlank()) transport.connect(settings.path("host").asText(),settings.path("port").asInt(),null,null);
            else {
                byte[] password=secrets.resolve(settings.path("passwordRef").asText());
                try { transport.connect(settings.path("host").asText(),settings.path("port").asInt(),username,new String(password,StandardCharsets.UTF_8)); }
                finally { Arrays.fill(password,(byte)0); }
            }
            transport.sendMessage(message,message.getAllRecipients());
        }
    }
    private Claim claim(UUID recipient) {
        var rows=jdbc.query("select settings::text,minimum_interval_seconds,channel from community.notification_recipient where id=? and enabled and deleted_at is null and next_at<=now() for update skip locked",
                (rs,row) -> new Recipient(rs.getString(1),rs.getInt(2),rs.getString(3)),recipient);
        if (rows.isEmpty()) return null;
        jdbc.update("""
                insert into community.notification_recipient_job_delivery(recipient_id,event_id,state)
                select r.id,e.id,'READY' from community.notification_recipient r join community.job_event e on e.occurred_at>=r.created_at
                where r.id=? and e.status=any(r.events) and e.status in ('RETRY','UNCERTAIN')
                  and not exists (select 1 from community.notification_recipient_job_delivery d where d.recipient_id=r.id and d.event_id=e.id)
                order by e.id limit 500 on conflict do nothing
                """,recipient);
        jdbc.update("update community.notification_recipient_job_delivery set state='RETRY',claim_token=null,lease_until=null where recipient_id=? and state='SENDING' and lease_until<now()",recipient);
        jdbc.update("""
                insert into community.notification_recipient_alert_delivery(recipient_id,event_id,state)
                select r.id,e.id,'READY' from community.notification_recipient r join community.operational_alert_event e on e.occurred_at>=r.created_at
                where r.id=? and e.event_type=any(r.events) and
                    (e.event_type='STORAGE_FULL' or (e.event_type='QUEUE_HIGH' and e.alert_key='queue:recipient:'||r.id::text))
                    and not exists (select 1 from community.notification_recipient_alert_delivery d where d.recipient_id=r.id and d.event_id=e.id)
                order by e.occurred_at,e.id limit 500 on conflict do nothing
                """,recipient);
        jdbc.update("update community.notification_recipient_alert_delivery set state='RETRY',claim_token=null,lease_until=null where recipient_id=? and state='SENDING' and lease_until<now()",recipient);
        jdbc.update("update community.notification_recipient_test_delivery set state='RETRY',claim_token=null,lease_until=null where recipient_id=? and state='SENDING' and lease_until<now()",recipient);
        var tests=jdbc.query("""
                select event_id,created_at::text,attempts from community.notification_recipient_test_delivery
                where recipient_id=? and state in ('READY','RETRY') and next_attempt_at<=now()
                order by next_attempt_at,event_id for update skip locked limit 1
                """,(rs,row) -> new Claim(recipient,0,null,rs.getObject(1,UUID.class),null,"TEST",0,rs.getString(2),null,rs.getInt(3)+1,UUID.randomUUID(),rows.get(0).settings(),rows.get(0).channel()),recipient);
        if (!tests.isEmpty()) {
            Claim result=tests.get(0);
            jdbc.update("update community.notification_recipient_test_delivery set state='SENDING',attempts=?,claim_token=?,lease_until=now()+interval '1 minute' where recipient_id=? and event_id=?",result.attempt(),result.token(),recipient,result.testId());
            jdbc.update("update community.notification_recipient set next_at=now()+(? * interval '1 second') where id=?",rows.get(0).interval(),recipient);
            return result;
        }
        var alerts=jdbc.query("""
                select d.event_id,e.event_type,e.alert_key,e.occurred_at::text,d.attempts
                from community.notification_recipient_alert_delivery d join community.operational_alert_event e on e.id=d.event_id
                where d.recipient_id=? and d.state in ('READY','RETRY') and d.next_attempt_at<=now()
                order by d.next_attempt_at,d.event_id for update of d skip locked limit 1
                """,(rs,row) -> new Claim(recipient,0,rs.getObject(1,UUID.class),null,null,rs.getString(2),0,rs.getString(4),rs.getString(3),rs.getInt(5)+1,UUID.randomUUID(),rows.get(0).settings(),rows.get(0).channel()),recipient);
        if (!alerts.isEmpty()) {
            Claim result=alerts.get(0);
            jdbc.update("update community.notification_recipient_alert_delivery set state='SENDING',attempts=?,claim_token=?,lease_until=now()+interval '1 minute' where recipient_id=? and event_id=?",result.attempt(),result.token(),recipient,result.alertId());
            jdbc.update("update community.notification_recipient set next_at=now()+(? * interval '1 second') where id=?",rows.get(0).interval(),recipient);
            return result;
        }
        var jobs=jdbc.query("""
                select d.event_id,e.job_id,e.status,e.attempt,e.occurred_at::text,d.attempts
                from community.notification_recipient_job_delivery d join community.job_event e on e.id=d.event_id
                where d.recipient_id=? and d.state in ('READY','RETRY') and d.next_attempt_at<=now()
                order by d.next_attempt_at,d.event_id for update of d skip locked limit 1
                """,(rs,row) -> new Claim(recipient,rs.getLong(1),null,null,rs.getObject(2,UUID.class),rs.getString(3),rs.getInt(4),rs.getString(5),null,rs.getInt(6)+1,UUID.randomUUID(),rows.get(0).settings(),rows.get(0).channel()),recipient);
        if (jobs.isEmpty()) return null;
        Claim result=jobs.get(0);
        jdbc.update("update community.notification_recipient_job_delivery set state='SENDING',attempts=?,claim_token=?,lease_until=now()+interval '1 minute' where recipient_id=? and event_id=?",result.attempt(),result.token(),recipient,result.jobEventId());
        jdbc.update("update community.notification_recipient set next_at=now()+(? * interval '1 second') where id=?",rows.get(0).interval(),recipient);
        return result;
    }
    private void finish(Claim claim,boolean delivered) {
        String table=claim.testId()!=null?"notification_recipient_test_delivery":claim.alertId()==null?"notification_recipient_job_delivery":"notification_recipient_alert_delivery";
        Object eventId=claim.testId()!=null?claim.testId():claim.alertId()==null?claim.jobEventId():claim.alertId();
        int changed=delivered
                ? jdbc.update("update community."+table+" set state='DELIVERED',delivered_at=now(),claim_token=null,lease_until=null where recipient_id=? and event_id=? and state='SENDING' and claim_token=?",claim.recipient(),eventId,claim.token())
                : jdbc.update("update community."+table+" set state='RETRY',next_attempt_at=now()+(? * interval '1 second'),claim_token=null,lease_until=null where recipient_id=? and event_id=? and state='SENDING' and claim_token=?",DirectoryDeliveryScheduler.retryDelaySeconds(claim.attempt(),10,3600),claim.recipient(),eventId,claim.token());
        if (changed==1) jdbc.update("insert into community.notification_attempt(channel,recipient_id,event_key,attempt,outcome) values (?,?,?,?,?)",claim.channel(),claim.recipient(),claim.eventKey(),claim.attempt(),delivered?"ACCEPTED":"RETRY");
    }
    private record Recipient(String settings,int interval,String channel) {}
    private record Claim(UUID recipient,long jobEventId,UUID alertId,UUID testId,UUID jobId,String type,int jobAttempt,String occurredAt,String reference,int attempt,UUID token,String settings,String channel) {
        String eventKey() { return testId!=null?"test:"+testId:alertId==null?Long.toString(jobEventId):"alert:"+alertId; }
    }
}
