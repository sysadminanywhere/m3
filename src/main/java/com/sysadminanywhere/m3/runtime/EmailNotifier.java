package com.sysadminanywhere.m3.runtime;

import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent, persistent and rate-limited SMTP delivery. SMTP acknowledgement is not inbox delivery. */
@Component
public class EmailNotifier {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RuntimeSecretResolver secrets;
    private final OperationalAlerts alerts;
    private final String host,user,passwordRef,from,to;
    private final int port;
    private final boolean plainLoopback;
    private final long interval,queueThreshold;
    private final java.util.Set<String> events;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task -> { var thread=new Thread(task,"m3-email-delivery"); thread.setDaemon(true); return thread; });
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();

    public EmailNotifier(JdbcTemplate jdbc,PlatformTransactionManager manager,
            @org.springframework.context.annotation.Lazy RuntimeSecretResolver secrets,
            @org.springframework.context.annotation.Lazy OperationalAlerts alerts,
            @Value("${m3.email.host:}") String host, @Value("${m3.email.port:587}") int port,
            @Value("${m3.email.username:}") String user, @Value("${m3.email.password-ref:}") String passwordRef,
            @Value("${m3.email.from:}") String from, @Value("${m3.email.to:}") String to,
            @Value("${m3.email.allow-plaintext-loopback:false}") boolean plainLoopback,
            @Value("${m3.email.minimum-interval-seconds:10}") long interval,
            @Value("${m3.email.events:RETRY,UNCERTAIN,STORAGE_FULL,QUEUE_HIGH}") String events,
            @Value("${m3.email.queue-threshold:0}") long queueThreshold) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager); this.secrets=secrets; this.alerts=alerts;
        this.host=host==null?"":host.trim(); this.port=port; this.user=user==null?"":user.trim(); this.passwordRef=passwordRef==null?"":passwordRef.trim();
        this.from=from==null?"":from.trim(); this.to=to==null?"":to.trim(); this.plainLoopback=plainLoopback;
        if (interval<1 || interval>3600 || queueThreshold<0) throw new IllegalArgumentException("Invalid email notification settings");
        this.interval=interval; this.queueThreshold=queueThreshold;
        this.events=Arrays.stream(events.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!java.util.Set.of("RETRY","UNCERTAIN","STORAGE_FULL","QUEUE_HIGH").containsAll(this.events)) throw new IllegalArgumentException("Invalid email events");
        if (!this.host.isEmpty()) {
            if (!this.host.matches("[A-Za-z0-9.:-]+") || port<1 || port>65535 || this.from.isBlank() || this.to.isBlank() || this.user.isBlank()!=this.passwordRef.isBlank()) throw new IllegalArgumentException("Invalid email notification settings");
            if (plainLoopback && !java.util.Set.of("localhost","127.0.0.1","::1").contains(this.host.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Plain SMTP is allowed only on loopback");
            try { new InternetAddress(this.from,true).validate(); new InternetAddress(this.to,true).validate(); }
            catch (jakarta.mail.internet.AddressException invalid) { throw new IllegalArgumentException("Invalid email address"); }
        }
    }
    @Scheduled(fixedDelayString="${m3.email.poll-ms:10000}") public void schedule() {
        if (host.isEmpty() || !running.compareAndSet(false,true)) return;
        try { worker.execute(() -> { try { poll(); } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(EmailNotifier.class).warn("Email processing failed; pending events will be retried");
        } finally { running.set(false); } }); } catch (java.util.concurrent.RejectedExecutionException stopped) { running.set(false); }
    }
    @jakarta.annotation.PreDestroy public void shutdown() { worker.shutdownNow(); }
    public void poll() {
        if (host.isEmpty()) return;
        if (events.contains("QUEUE_HIGH") && queueThreshold>0) {
            Long queued=jdbc.queryForObject("select count(*) from community.execution_job where status in ('READY','RETRY')",Long.class);
            alerts.set("queue:email","QUEUE_HIGH",queued!=null && queued>=queueThreshold);
        }
        Claim claim=transactions.execute(status -> claim()); if (claim==null) return;
        boolean sent=false;
        try { send(claim); sent=true; } catch (Exception failure) { /* No server response, password or address is logged. */ }
        finish(claim,sent);
    }
    private Claim claim() {
        jdbc.update("""
                insert into community.email_job_delivery(event_id,state)
                select e.id,'READY' from community.job_event e where ((? and e.status='RETRY') or (? and e.status='UNCERTAIN'))
                and e.occurred_at>=now()-interval '1 day' and not exists (select 1 from community.email_job_delivery d where d.event_id=e.id)
                order by e.id limit 500 on conflict(event_id) do nothing
                """,events.contains("RETRY"),events.contains("UNCERTAIN"));
        jdbc.update("""
                insert into community.email_alert_delivery(event_id,state)
                select e.id,'READY' from community.operational_alert_event e
                where ((? and e.event_type='STORAGE_FULL') or (? and e.event_type='QUEUE_HIGH' and e.alert_key='queue:email')) and e.occurred_at>=now()-interval '1 day'
                and not exists (select 1 from community.email_alert_delivery d where d.event_id=e.id)
                order by e.occurred_at,e.id limit 500 on conflict(event_id) do nothing
                """,events.contains("STORAGE_FULL"),events.contains("QUEUE_HIGH"));
        for (String table : java.util.List.of("email_job_delivery","email_alert_delivery"))
            jdbc.update("update community."+table+" set state='RETRY',claim_token=null,lease_until=null where state='SENDING' and lease_until<now()");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select next_at<=now() from community.email_rate_limit where singleton=true for update",Boolean.class))) return null;
        var alerts=jdbc.query("""
                select d.event_id,e.event_type,e.alert_key,e.occurred_at::text,d.attempts from community.email_alert_delivery d
                join community.operational_alert_event e on e.id=d.event_id where d.state in ('READY','RETRY') and d.next_attempt_at<=now()
                order by d.next_attempt_at,d.event_id for update of d skip locked limit 1
                """,(rs,row) -> new Claim(0,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)+1,UUID.randomUUID()));
        Claim result;
        if (!alerts.isEmpty()) result=alerts.get(0);
        else {
            var jobs=jdbc.query("""
                    select d.event_id,e.job_id,e.status,e.occurred_at::text,d.attempts from community.email_job_delivery d
                    join community.job_event e on e.id=d.event_id where d.state in ('READY','RETRY') and d.next_attempt_at<=now()
                    order by d.next_attempt_at,d.event_id for update of d skip locked limit 1
                    """,(rs,row) -> new Claim(rs.getLong(1),null,rs.getString(3),rs.getString(2),rs.getString(4),rs.getInt(5)+1,UUID.randomUUID()));
            if (jobs.isEmpty()) return null; result=jobs.get(0);
        }
        String table=result.alertId()==null?"email_job_delivery":"email_alert_delivery"; Object id=result.alertId()==null?result.jobEventId():result.alertId();
        jdbc.update("update community."+table+" set state='SENDING',attempts=?,claim_token=?,lease_until=now()+interval '1 minute' where event_id=?",result.attempt(),result.token(),id);
        jdbc.update("update community.email_rate_limit set next_at=now()+(? * interval '1 second') where singleton=true",interval);
        return result;
    }
    private void send(Claim claim) throws Exception {
        Properties properties=new Properties(); properties.setProperty("mail.smtp.connectiontimeout","5000"); properties.setProperty("mail.smtp.timeout","10000"); properties.setProperty("mail.smtp.writetimeout","10000");
        if (!plainLoopback) { properties.setProperty("mail.smtp.starttls.enable","true"); properties.setProperty("mail.smtp.starttls.required","true"); properties.setProperty("mail.smtp.ssl.checkserveridentity","true"); }
        Session session=Session.getInstance(properties); var message=new MimeMessage(session);
        message.setFrom(new InternetAddress(from,true)); message.setRecipient(Message.RecipientType.TO,new InternetAddress(to,true));
        String id=claim.alertId()==null?Long.toString(claim.jobEventId()):"alert:"+claim.alertId();
        message.setSubject("M3: "+claim.type(),"UTF-8"); message.setText("Event: "+id+"\nType: "+claim.type()+"\nReference: "+claim.reference()+"\nOccurred: "+claim.occurredAt(),"UTF-8");
        message.setHeader("X-M3-Event-Id",id); message.saveChanges();
        try (Transport transport=session.getTransport("smtp")) {
            if (user.isEmpty()) transport.connect(host,port,null,null);
            else {
                byte[] password=secrets.resolve(passwordRef);
                try { transport.connect(host,port,user,new String(password,StandardCharsets.UTF_8)); }
                finally { Arrays.fill(password,(byte)0); }
            }
            transport.sendMessage(message,message.getAllRecipients());
        }
    }
    private void finish(Claim claim,boolean delivered) {
        String table=claim.alertId()==null?"email_job_delivery":"email_alert_delivery"; Object id=claim.alertId()==null?claim.jobEventId():claim.alertId();
        transactions.executeWithoutResult(status -> {
            int changed=delivered
                    ? jdbc.update("update community."+table+" set state='DELIVERED',delivered_at=now(),claim_token=null,lease_until=null where event_id=? and state='SENDING' and claim_token=?",id,claim.token())
                    : jdbc.update("update community."+table+" set state='RETRY',next_attempt_at=now()+(? * interval '1 second'),claim_token=null,lease_until=null where event_id=? and state='SENDING' and claim_token=?",DirectoryDeliveryScheduler.retryDelaySeconds(claim.attempt(),10,3600),id,claim.token());
            if (changed==1) jdbc.update("insert into community.notification_attempt(channel,event_key,attempt,outcome) values ('email',?,?,?)",
                    claim.alertId()==null?Long.toString(claim.jobEventId()):"alert:"+claim.alertId(),claim.attempt(),delivered?"ACCEPTED":"RETRY");
        });
    }
    private record Claim(long jobEventId,UUID alertId,String type,String reference,String occurredAt,int attempt,UUID token) {}
}
