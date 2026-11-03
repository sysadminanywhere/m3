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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, rate-limited at-least-once webhook delivery for job state changes. */
@Component
public class WebhookNotifier {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final RuntimeSecretResolver secrets;
    private final URI endpoint;
    private final String tokenRef;
    private final HttpClient http;
    private final long rateSeconds;
    private final boolean notifyRetry, notifyUncertain, notifyStorageFull, notifyQueueHigh;
    private final long queueThreshold;
    private final OperationalAlerts alerts;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(task,"m3-webhook-delivery"); thread.setDaemon(true); return thread;
    });
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookNotifier(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper,
            @org.springframework.context.annotation.Lazy RuntimeSecretResolver secrets,
            @Value("${m3.webhook.url:}") String endpoint,
            @Value("${m3.webhook.token-ref:}") String tokenRef,
            @Value("${m3.webhook.minimum-interval-seconds:10}") long rateSeconds,
            @Value("${m3.webhook.events:RETRY,UNCERTAIN,STORAGE_FULL,QUEUE_HIGH}") String events,
            @Value("${m3.webhook.queue-threshold:0}") long queueThreshold,
            @org.springframework.context.annotation.Lazy OperationalAlerts alerts) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager); this.mapper=mapper; this.secrets=secrets;
        this.endpoint=endpoint==null || endpoint.isBlank()?null:validateEndpoint(endpoint);
        this.tokenRef=tokenRef==null?"":tokenRef.trim();
        if (rateSeconds<1 || rateSeconds>3600) throw new IllegalArgumentException("Invalid webhook rate interval");
        this.rateSeconds=rateSeconds;
        var selected=Arrays.stream(events.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
        if (!java.util.Set.of("RETRY","UNCERTAIN","STORAGE_FULL","QUEUE_HIGH").containsAll(selected)) throw new IllegalArgumentException("Invalid webhook events");
        this.notifyRetry=selected.contains("RETRY"); this.notifyUncertain=selected.contains("UNCERTAIN");
        this.notifyStorageFull=selected.contains("STORAGE_FULL"); this.notifyQueueHigh=selected.contains("QUEUE_HIGH");
        if (queueThreshold<0) throw new IllegalArgumentException("Invalid webhook queue threshold");
        this.queueThreshold=queueThreshold; this.alerts=alerts;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.of(null)).build();
    }
    public WebhookNotifier(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper,RuntimeSecretResolver secrets,
            String endpoint,String tokenRef,long rateSeconds,String events) {
        this(jdbc,manager,mapper,secrets,endpoint,tokenRef,rateSeconds,events,0,null);
    }
    private static URI validateEndpoint(String value) {
        URI uri=URI.create(value); String host=uri.getHost();
        boolean local=host!=null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]"));
        if (host==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null
                || !("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme()))))
            throw new IllegalArgumentException("Webhook requires HTTPS or loopback HTTP and a URL without credentials or query");
        return uri;
    }
    @Scheduled(fixedDelayString="${m3.webhook.poll-ms:10000}")
    public void schedule() {
        if (endpoint==null || !running.compareAndSet(false,true)) return;
        try { worker.execute(() -> { try { poll(); } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(WebhookNotifier.class).warn("Webhook processing failed; pending events will be retried");
        } finally { running.set(false); } }); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { running.set(false); }
    }
    @jakarta.annotation.PreDestroy public void shutdown() { worker.shutdownNow(); }
    public void poll() {
        if (endpoint==null) return;
        if (notifyQueueHigh && queueThreshold>0 && alerts!=null) {
            Long queued=jdbc.queryForObject("select count(*) from community.execution_job where status in ('READY','RETRY')",Long.class);
            alerts.set("queue:global","QUEUE_HIGH",queued!=null && queued>=queueThreshold);
        }
        Claim claim=transactions.execute(status -> claim());
        if (claim==null) return;
        boolean delivered=false;
        try {
            String eventKey=claim.alertId()==null?Long.toString(claim.eventId()):"alert:"+claim.alertId();
            var payload=mapper.createObjectNode().put("eventId",eventKey).put("occurredAt",claim.occurredAt());
            if (claim.alertId()==null) payload.put("jobId",claim.jobId().toString()).put("status",claim.status()).put("attempt",claim.attempt());
            else payload.put("type",claim.status()).put("alertKey",claim.alertKey());
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                    .header("X-M3-Event-Id",eventKey).POST(HttpRequest.BodyPublishers.ofString(payload.toString(),StandardCharsets.UTF_8));
            if (!tokenRef.isBlank()) {
                byte[] token=secrets.resolve(tokenRef);
                try {
                    String value=new String(token,StandardCharsets.UTF_8);
                    if (value.isBlank() || value.chars().anyMatch(c -> c<33 || c>126)) throw new IllegalStateException("Invalid webhook token");
                    request.header("Authorization","Bearer "+value);
                } finally { Arrays.fill(token,(byte)0); }
            }
            int status=http.send(request.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
            delivered=status>=200 && status<300;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { /* Never log endpoint, credentials, response body or server-provided errors. */ }
        finish(claim,delivered);
    }
    private Claim claim() {
        jdbc.update("""
                insert into community.webhook_delivery(event_id,state)
                select e.id,'READY' from community.job_event e where ((? and e.status='RETRY') or (? and e.status='UNCERTAIN')) and e.occurred_at >= now()-interval '1 day'
                and not exists (select 1 from community.webhook_delivery d where d.event_id=e.id)
                order by e.id limit 500 on conflict(event_id) do nothing
                """,notifyRetry,notifyUncertain);
        jdbc.update("update community.webhook_delivery set state='RETRY',claim_token=null,lease_until=null where state='SENDING' and lease_until<now()");
        jdbc.update("""
                insert into community.webhook_alert_delivery(event_id,state)
                select e.id,'READY' from community.operational_alert_event e
                where ((? and e.event_type='STORAGE_FULL') or (? and e.event_type='QUEUE_HIGH' and e.alert_key='queue:global')) and e.occurred_at>=now()-interval '1 day'
                and not exists (select 1 from community.webhook_alert_delivery d where d.event_id=e.id)
                order by e.occurred_at,e.id limit 500 on conflict(event_id) do nothing
                """,notifyStorageFull,notifyQueueHigh);
        jdbc.update("update community.webhook_alert_delivery set state='RETRY',claim_token=null,lease_until=null where state='SENDING' and lease_until<now()");
        var permitted=jdbc.queryForObject("select next_at<=now() from community.webhook_rate_limit where singleton=true for update",Boolean.class);
        if (!Boolean.TRUE.equals(permitted)) return null;
        var alertCandidates=jdbc.query("""
                select d.event_id,e.event_type,e.alert_key,e.occurred_at::text,d.attempts
                from community.webhook_alert_delivery d join community.operational_alert_event e on e.id=d.event_id
                where d.state in ('READY','RETRY') and d.next_attempt_at<=now()
                order by d.next_attempt_at,d.event_id for update of d skip locked limit 1
                """,(rs,row) -> new Claim(0,null,rs.getString(2),0,rs.getString(4),rs.getInt(5)+1,UUID.randomUUID(),rs.getObject(1,UUID.class),rs.getString(3)));
        if (!alertCandidates.isEmpty()) {
            Claim result=alertCandidates.get(0);
            jdbc.update("update community.webhook_alert_delivery set state='SENDING',attempts=?,claim_token=?,lease_until=now()+interval '1 minute' where event_id=?",result.deliveryAttempt(),result.token(),result.alertId());
            jdbc.update("update community.webhook_rate_limit set next_at=now()+(? * interval '1 second') where singleton=true",rateSeconds);
            return result;
        }
        var candidates=jdbc.query("""
                select d.event_id,e.job_id,e.status,e.attempt,e.occurred_at::text,d.attempts
                from community.webhook_delivery d join community.job_event e on e.id=d.event_id
                where d.state in ('READY','RETRY') and d.next_attempt_at<=now()
                order by d.next_attempt_at,d.event_id for update of d skip locked limit 1
                """,(rs,row) -> new Claim(rs.getLong(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getInt(6)+1,UUID.randomUUID(),null,null));
        if (candidates.isEmpty()) return null;
        Claim result=candidates.get(0);
        jdbc.update("update community.webhook_delivery set state='SENDING',attempts=?,claim_token=?,lease_until=now()+interval '1 minute' where event_id=?",result.deliveryAttempt(),result.token(),result.eventId());
        jdbc.update("update community.webhook_rate_limit set next_at=now()+(? * interval '1 second') where singleton=true",rateSeconds);
        return result;
    }
    private void finish(Claim claim,boolean delivered) {
        transactions.executeWithoutResult(status -> {
            String table=claim.alertId()==null?"webhook_delivery":"webhook_alert_delivery";
            Object id=claim.alertId()==null?claim.eventId():claim.alertId();
            int changed=delivered
                    ? jdbc.update("update community."+table+" set state='DELIVERED',delivered_at=now(),claim_token=null,lease_until=null where event_id=? and state='SENDING' and claim_token=?",id,claim.token())
                    : jdbc.update("update community."+table+" set state='RETRY',next_attempt_at=now()+(? * interval '1 second'),claim_token=null,lease_until=null where event_id=? and state='SENDING' and claim_token=?",DirectoryDeliveryScheduler.retryDelaySeconds(claim.deliveryAttempt(),10,3600),id,claim.token());
            if (changed==1) jdbc.update("insert into community.notification_attempt(channel,event_key,attempt,outcome) values ('webhook',?,?,?)",
                    claim.alertId()==null?Long.toString(claim.eventId()):"alert:"+claim.alertId(),claim.deliveryAttempt(),delivered?"ACCEPTED":"RETRY");
        });
    }
    private record Claim(long eventId,UUID jobId,String status,int attempt,String occurredAt,int deliveryAttempt,UUID token,UUID alertId,String alertKey) {}
}
