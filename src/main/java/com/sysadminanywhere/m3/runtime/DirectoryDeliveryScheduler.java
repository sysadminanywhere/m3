package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sysadminanywhere.m3.security.RuntimeSecretResolver;

/** PostgreSQL-backed work dispatcher. SKIP LOCKED allows identical Community replicas to share work. */
@Component
public class DirectoryDeliveryScheduler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String workerId;
    private final String workerGroup;
    private final TransactionTemplate transactions;
    private final long retryBaseSeconds;
    private final long retryMaxSeconds;
    private final boolean allowAutoRetryUncertain;
    private final RuntimeSecretResolver secrets;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectoryDeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager,
                                      @Value("${m3.worker.id:${HOSTNAME:community-local}}") String workerId,
                                      @Value("${M3_EXECUTION_GROUP:${m3.worker.group:default}}") String workerGroup,
                                      @Value("${m3.delivery.retry-base-seconds:1}") long retryBaseSeconds,
                                      @Value("${m3.delivery.retry-max-seconds:300}") long retryMaxSeconds,
                                      @Value("${m3.delivery.allow-auto-retry-uncertain:false}") boolean allowAutoRetryUncertain,
                                      @org.springframework.context.annotation.Lazy RuntimeSecretResolver secrets) {
        if (retryBaseSeconds < 1 || retryMaxSeconds < retryBaseSeconds) throw new IllegalArgumentException("Delivery retry seconds must be positive and max must be at least base");
        this.jdbc = jdbc; this.mapper = mapper; this.workerId = workerId + "-" + UUID.randomUUID();
        this.workerGroup = workerGroup == null || workerGroup.isBlank() ? "default" : workerGroup;
        this.transactions = new TransactionTemplate(transactionManager);
        this.retryBaseSeconds = retryBaseSeconds; this.retryMaxSeconds = retryMaxSeconds; this.allowAutoRetryUncertain = allowAutoRetryUncertain;
        this.secrets=secrets;
    }

    public DirectoryDeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager, String workerId, String workerGroup, long retryBaseSeconds, long retryMaxSeconds, boolean allowAutoRetryUncertain) {
        this(jdbc,mapper,transactionManager,workerId,workerGroup,retryBaseSeconds,retryMaxSeconds,allowAutoRetryUncertain,null);
    }

    public DirectoryDeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager, String workerId, String workerGroup) {
        this(jdbc, mapper, transactionManager, workerId, workerGroup, 1, 300, false);
    }
    public DirectoryDeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager, String workerId, String workerGroup, long retryBaseSeconds, long retryMaxSeconds) {
        this(jdbc, mapper, transactionManager, workerId, workerGroup, retryBaseSeconds, retryMaxSeconds, false);
    }

    @Scheduled(fixedDelayString = "${m3.delivery.fixed-delay-ms:1000}")
    public void dispatch() {
        recoverExpiredLeases();
        if (allowAutoRetryUncertain) scheduleUncertainRetries();
        for (Job job : claim(1)) deliver(job);
    }

    private void scheduleUncertainRetries() {
        transactions.executeWithoutResult(status -> {
            var jobs = jdbc.query("""
                    select j.id,j.attempts from community.execution_job j
                    join community.config_bundle c on c.version=j.rule_version
                    where j.status='UNCERTAIN' and j.execution_group=? and exists (
                      select 1 from jsonb_array_elements(c.document_json->'rules') r
                      where r->>'id'=j.rule_id and r->'autoRetryUncertain' = 'true'::jsonb)
                    order by j.created_at,j.id for update of j skip locked limit 100
                    """, (rs,row) -> new UncertainJob(UUID.fromString(rs.getString(1)),rs.getInt(2)), workerGroup);
            for (var job : jobs) jdbc.update("update community.execution_job set status='RETRY',next_attempt_at=now()+(? * interval '1 second') where id=? and status='UNCERTAIN'",
                    retryDelaySeconds(job.attempts(), retryBaseSeconds, retryMaxSeconds), job.id());
        });
    }

    private void recoverExpiredLeases() {
        jdbc.update("update community.execution_job set status='UNCERTAIN', claimed_by=null, lease_until=null, last_error='Lease expired; delivery outcome requires operator review' where status='RUNNING' and lease_until < now()");
    }

    private List<Job> claim(int limit) {
        return transactions.execute(status -> {
        jdbc.execute("select pg_advisory_xact_lock(8273642)");
        String sql = """
                with candidate as (select j.id from community.execution_job j
                  join community.config_bundle c on c.version=j.rule_version
                  cross join lateral (select r from jsonb_array_elements(c.document_json->'rules') r where r->>'id'=j.rule_id limit 1) rule
                  where j.execution_group = ? and j.status in ('READY','RETRY') and j.next_attempt_at <= now()
                  and (coalesce(rule.r->>'processingMode','PARALLEL') <> 'SEQUENTIAL' or not exists (
                    select 1 from community.execution_job earlier
                    where earlier.rule_id=j.rule_id and earlier.execution_group=j.execution_group
                      and earlier.id<>j.id
                      and (earlier.status='RUNNING' or ((earlier.created_at, earlier.id) < (j.created_at, j.id)
                        and (earlier.status='READY' or (earlier.status in ('RETRY','UNCERTAIN') and coalesce(rule.r->>'onError','STOP')='STOP'))))))
                  order by j.created_at, j.id for update of j skip locked limit ?)
                update community.execution_job j set status='RUNNING', claimed_by=?, lease_until=now() + interval '60 seconds', attempts=attempts+1
                from candidate where j.id=candidate.id
                returning j.id, j.message_id, j.rule_id, j.rule_version, j.attempts
                """;
        return jdbc.query(sql, (rs, row) -> new Job(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("message_id")),
                rs.getString("rule_id"), rs.getInt("rule_version"), rs.getInt("attempts")), workerGroup, limit, workerId);
        });
    }

    private void deliver(Job job) {
        AtomicBoolean lost = new AtomicBoolean(false);
        AtomicBoolean publicationStarted = new AtomicBoolean(false);
        AtomicBoolean remoteDelivery = new AtomicBoolean(false);
        var heartbeat = Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "m3-delivery-lease"); thread.setDaemon(true); return thread; });
        heartbeat.scheduleAtFixedRate(() -> {
            try { if (!renew(job)) lost.set(true); }
            catch (Exception failure) { lost.set(true); }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            JobPayload payload = jdbc.queryForObject("""
                    select m.storage_key, m.original_name, c.document_json, m.checksum from community.execution_job j
                    join community.message_record m on m.id=j.message_id join community.config_bundle c on c.version=j.rule_version where j.id=?
                    """, (rs, row) -> new JobPayload(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), job.id());
            JsonNode configuration = mapper.readTree(payload.configJson());
            JsonNode rule = find(configuration.path("rules"), job.ruleId());
            JsonNode recipient = find(configuration.path("recipients"), rule.path("recipientId").asText());
            if ("sftp".equals(recipient.path("type").asText())) {
                remoteDelivery.set(true);
                if (secrets==null) throw new IllegalStateException("SFTP secret resolver unavailable");
                JsonNode settings=recipient.path("settings");
                try (var connection=SftpConnection.open(settings.path("host").asText(),settings.path("port").asInt(22),
                        settings.path("username").asText(),settings.path("hostKeyFingerprint").asText(),settings.path("credentialRef").asText(),
                        settings.path("authMethod").asText("PASSWORD"),settings.path("credentialProvider").asText("COMMUNITY"),java.time.Duration.ofSeconds(30),secrets)) {
                    SftpPublisher.publish(connection.client(),Path.of(payload.storageKey()),settings.path("path").asText(),job.id()+"-"+payload.originalName(),payload.checksum(),() -> {
                        if (lost.get() || !renew(job)) throw new java.io.IOException("Delivery lease lost before publication");
                        publicationStarted.set(true);
                    });
                }
                jdbc.update("update community.execution_job set status='DELIVERED', completed_at=now(), lease_until=null, claimed_by=null, last_error=null where id=? and status='RUNNING' and claimed_by=? and attempts=? and lease_until > now()",job.id(),workerId,job.attempts());
                return;
            }
            if ("http".equals(recipient.path("type").asText())) {
                remoteDelivery.set(true); JsonNode settings=recipient.path("settings");
                String tokenRef=settings.path("tokenRef").asText("");
                if (!tokenRef.isBlank() && secrets==null) throw new IllegalStateException("HTTP secret resolver unavailable");
                HttpPublisher.publish(settings.path("url").asText(),tokenRef,Path.of(payload.storageKey()),payload.checksum(),job.id(),secrets,() -> {
                    if (lost.get() || !renew(job)) throw new java.io.IOException("Delivery lease lost before publication");
                    publicationStarted.set(true);
                });
                jdbc.update("update community.execution_job set status='DELIVERED', completed_at=now(), lease_until=null, claimed_by=null, last_error=null where id=? and status='RUNNING' and claimed_by=? and attempts=? and lease_until > now()",job.id(),workerId,job.attempts());
                return;
            }
            if (!"directory".equals(recipient.path("type").asText())) throw new IllegalStateException("Unsupported recipient type");
            Path outputDirectory = Path.of(recipient.path("settings").path("path").asText());
            if (recipient.path("settings").path("path").asText().isBlank()) throw new IllegalStateException("Recipient directory is not configured");
            if (!renew(job) || lost.get()) return;
            Files.createDirectories(outputDirectory);
            Path target = outputDirectory.resolve(job.id() + "-" + payload.originalName());
            copyAtomically(Path.of(payload.storageKey()), target, payload.checksum(), () -> {
                if (lost.get() || !renew(job)) throw new java.io.IOException("Delivery lease lost before publication");
                publicationStarted.set(true);
            });
            jdbc.update("update community.execution_job set status='DELIVERED', completed_at=now(), lease_until=null, claimed_by=null, last_error=null where id=? and status='RUNNING' and claimed_by=? and attempts=? and lease_until > now()", job.id(), workerId, job.attempts());
        } catch (Exception error) { recordFailure(job, remoteDelivery.get()?new java.io.IOException("Remote delivery failed"):error, publicationStarted.get()); }
        finally { heartbeat.shutdownNow(); }
    }

    private boolean renew(Job job) {
        return jdbc.update("update community.execution_job set lease_until=now() + interval '60 seconds' where id=? and status='RUNNING' and claimed_by=? and attempts=? and lease_until > now()", job.id(), workerId, job.attempts()) == 1;
    }

    static void copyAtomically(Path source, Path target) throws java.io.IOException {
        copyAtomically(source, target, () -> {});
    }

    @FunctionalInterface interface BeforePublish { void check() throws java.io.IOException; }
    static void copyAtomically(Path source, Path target, BeforePublish beforePublish) throws java.io.IOException {
        copyAtomically(source, target, null, beforePublish);
    }

    static void copyAtomically(Path source, Path target, String expectedChecksum, BeforePublish beforePublish) throws java.io.IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + ".part-" + UUID.randomUUID());
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (expectedChecksum != null) {
                try {
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var input = Files.newInputStream(temporary)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); }
                    if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(expectedChecksum)) throw new java.io.IOException("Original content checksum mismatch");
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            }
            beforePublish.check();
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void recordFailure(Job job, Exception error, boolean publicationStarted) {
        if (publicationStarted) {
            jdbc.update("update community.execution_job set status='UNCERTAIN', lease_until=null, claimed_by=null, last_error=? where id=? and status='RUNNING' and claimed_by=? and attempts=?",
                    truncate(error.getMessage()), job.id(), workerId, job.attempts());
        } else {
            jdbc.update("update community.execution_job set status='RETRY', next_attempt_at=now() + (? * interval '1 second'), lease_until=null, claimed_by=null, last_error=? where id=? and status='RUNNING' and claimed_by=? and attempts=? and lease_until > now()",
                    retryDelaySeconds(job.attempts(), retryBaseSeconds, retryMaxSeconds), truncate(error.getMessage()), job.id(), workerId, job.attempts());
        }
    }

    static long retryDelaySeconds(int attempts) {
        return retryDelaySeconds(attempts, 1, 300);
    }
    static long retryDelaySeconds(int attempts, long base, long max) {
        long delay = base;
        for (int i = 0; i < attempts && delay < max; i++) {
            if (delay > max / 2) return max;
            delay *= 2;
        }
        return Math.min(delay, max);
    }

    static String truncate(String input) { return input == null ? "unknown failure" : input.substring(0, Math.min(input.length(), 1000)); }
    private static JsonNode find(JsonNode array, String id) { for (JsonNode n : array) if (id.equals(n.path("id").asText())) return n; return com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }
    record Job(UUID id, UUID messageId, String ruleId, int ruleVersion, int attempts) {}
    private record UncertainJob(UUID id, int attempts) {}
    record JobPayload(String storageKey, String originalName, String configJson, String checksum) {}
}
