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

/** PostgreSQL-backed work dispatcher. SKIP LOCKED allows identical Community replicas to share work. */
@Component
public class DirectoryDeliveryScheduler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String workerId;
    private final String workerGroup;
    private final TransactionTemplate transactions;

    public DirectoryDeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager,
                                      @Value("${m3.worker.id:${HOSTNAME:community-local}}") String workerId,
                                      @Value("${M3_EXECUTION_GROUP:${m3.worker.group:default}}") String workerGroup) {
        this.jdbc = jdbc; this.mapper = mapper; this.workerId = workerId + "-" + UUID.randomUUID();
        this.workerGroup = workerGroup == null || workerGroup.isBlank() ? "default" : workerGroup;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${m3.delivery.fixed-delay-ms:1000}")
    public void dispatch() {
        recoverExpiredLeases();
        for (Job job : claim(1)) deliver(job);
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
        var heartbeat = Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "m3-delivery-lease"); thread.setDaemon(true); return thread; });
        heartbeat.scheduleAtFixedRate(() -> {
            try { if (!renew(job)) lost.set(true); }
            catch (Exception failure) { lost.set(true); }
        }, 15, 15, TimeUnit.SECONDS);
        try {
            JobPayload payload = jdbc.queryForObject("""
                    select m.storage_key, m.original_name, c.document_json from community.execution_job j
                    join community.message_record m on m.id=j.message_id join community.config_bundle c on c.version=j.rule_version where j.id=?
                    """, (rs, row) -> new JobPayload(rs.getString(1), rs.getString(2), rs.getString(3)), job.id());
            JsonNode configuration = mapper.readTree(payload.configJson());
            JsonNode rule = find(configuration.path("rules"), job.ruleId());
            JsonNode recipient = find(configuration.path("recipients"), rule.path("recipientId").asText());
            if (!"directory".equals(recipient.path("type").asText())) throw new IllegalStateException("Only directory recipients are supported in v2 first release");
            Path outputDirectory = Path.of(recipient.path("settings").path("path").asText());
            if (recipient.path("settings").path("path").asText().isBlank()) throw new IllegalStateException("Recipient directory is not configured");
            if (!renew(job) || lost.get()) return;
            Files.createDirectories(outputDirectory);
            Path target = outputDirectory.resolve(job.id() + "-" + payload.originalName());
            copyAtomically(Path.of(payload.storageKey()), target, () -> {
                if (lost.get() || !renew(job)) throw new java.io.IOException("Delivery lease lost before publication");
                publicationStarted.set(true);
            });
            jdbc.update("update community.execution_job set status='DELIVERED', completed_at=now(), lease_until=null, claimed_by=null, last_error=null where id=? and status='RUNNING' and claimed_by=? and lease_until > now()", job.id(), workerId);
        } catch (Exception error) { recordFailure(job, error, publicationStarted.get()); }
        finally { heartbeat.shutdownNow(); }
    }

    private boolean renew(Job job) {
        return jdbc.update("update community.execution_job set lease_until=now() + interval '60 seconds' where id=? and status='RUNNING' and claimed_by=? and lease_until > now()", job.id(), workerId) == 1;
    }

    static void copyAtomically(Path source, Path target) throws java.io.IOException {
        copyAtomically(source, target, () -> {});
    }

    @FunctionalInterface interface BeforePublish { void check() throws java.io.IOException; }
    static void copyAtomically(Path source, Path target, BeforePublish beforePublish) throws java.io.IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + ".part-" + UUID.randomUUID());
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
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
            jdbc.update("update community.execution_job set status='UNCERTAIN', lease_until=null, claimed_by=null, last_error=? where id=? and status='RUNNING' and claimed_by=?",
                    truncate(error.getMessage()), job.id(), workerId);
        } else {
            jdbc.update("update community.execution_job set status='RETRY', next_attempt_at=now() + (? * interval '1 second'), lease_until=null, claimed_by=null, last_error=? where id=? and status='RUNNING' and claimed_by=? and lease_until > now()",
                    retryDelaySeconds(job.attempts()), truncate(error.getMessage()), job.id(), workerId);
        }
    }

    static long retryDelaySeconds(int attempts) {
        if (attempts < 1) return 1;
        return Math.min(300, 1L << Math.min(attempts, 9));
    }

    static String truncate(String input) { return input == null ? "unknown failure" : input.substring(0, Math.min(input.length(), 1000)); }
    private static JsonNode find(JsonNode array, String id) { for (JsonNode n : array) if (id.equals(n.path("id").asText())) return n; return com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }
    record Job(UUID id, UUID messageId, String ruleId, int ruleVersion, int attempts) {}
    record JobPayload(String storageKey, String originalName, String configJson) {}
}
