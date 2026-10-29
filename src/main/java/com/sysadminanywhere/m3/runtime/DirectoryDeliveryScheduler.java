package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-backed work dispatcher. SKIP LOCKED allows identical Community replicas to share work. */
@Component
public class DirectoryDeliveryScheduler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String workerId;

    public DirectoryDeliveryScheduler(JdbcTemplate jdbc, ObjectMapper mapper,
                                      @Value("${m3.worker.id:${HOSTNAME:community-local}}") String workerId) {
        this.jdbc = jdbc; this.mapper = mapper; this.workerId = workerId + "-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${m3.delivery.fixed-delay-ms:1000}")
    public void dispatch() {
        recoverExpiredLeases();
        for (Job job : claim(10)) deliver(job);
    }

    private void recoverExpiredLeases() {
        jdbc.update("update community.execution_job set status='READY', claimed_by=null, lease_until=null, next_attempt_at=now(), last_error='Lease expired before completion' where status='RUNNING' and lease_until < now()");
    }

    private List<Job> claim(int limit) {
        String sql = """
                with candidate as (select id from community.execution_job where status = 'READY' and next_attempt_at <= now()
                  order by created_at for update skip locked limit ?)
                update community.execution_job j set status='RUNNING', claimed_by=?, lease_until=now() + interval '60 seconds', attempts=attempts+1
                from candidate where j.id=candidate.id
                returning j.id, j.message_id, j.rule_id, j.rule_version, j.attempts
                """;
        return jdbc.query(sql, (rs, row) -> new Job(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("message_id")),
                rs.getString("rule_id"), rs.getInt("rule_version"), rs.getInt("attempts")), limit, workerId);
    }

    private void deliver(Job job) {
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
            Files.createDirectories(outputDirectory);
            Path target = outputDirectory.resolve(job.id() + "-" + payload.originalName());
            Files.copy(Path.of(payload.storageKey()), target, StandardCopyOption.REPLACE_EXISTING);
            jdbc.update("update community.execution_job set status='DELIVERED', completed_at=now(), lease_until=null, claimed_by=null, last_error=null where id=? and status='RUNNING' and claimed_by=?", job.id(), workerId);
        } catch (Exception error) { retry(job, error); }
    }

    private void retry(Job job, Exception error) {
        long seconds = Math.min(300, (long) Math.pow(2, Math.min(job.attempts(), 8)));
        jdbc.update("update community.execution_job set status='READY', next_attempt_at=now() + (? * interval '1 second'), lease_until=null, claimed_by=null, last_error=? where id=? and status='RUNNING' and claimed_by=?",
                seconds, truncate(error.getMessage()), job.id(), workerId);
    }

    static String truncate(String input) { return input == null ? "unknown failure" : input.substring(0, Math.min(input.length(), 1000)); }
    private static JsonNode find(JsonNode array, String id) { for (JsonNode n : array) if (id.equals(n.path("id").asText())) return n; return com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }
    record Job(UUID id, UUID messageId, String ruleId, int ruleVersion, int attempts) {}
    record JobPayload(String storageKey, String originalName, String configJson) {}
}
