package com.sysadminanywhere.m3.api;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v2")
public class OperationsController {
    private final JdbcTemplate jdbc;
    public OperationsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/messages") public List<MessageSummary> messages() {
        return jdbc.query("select id, source_id, checksum, original_name, status, received_at, retain_until from community.message_record order by received_at desc limit 200",
                (rs, row) -> new MessageSummary(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class)));
    }
    @GetMapping("/jobs") public List<JobSummary> jobs() {
        return jdbc.query("select id, message_id, rule_id, rule_version, execution_group, status, attempts, last_error, created_at, completed_at from community.execution_job order by created_at desc limit 500",
                (rs, row) -> new JobSummary(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class)));
    }
    @GetMapping("/jobs/{id}/history") public List<JobEvent> history(@PathVariable UUID id) {
        return jdbc.query("select status,attempt,worker_id,error,occurred_at from community.job_event where job_id=? order by id", (rs,row) -> new JobEvent(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getObject(5,OffsetDateTime.class)), id);
    }
    @GetMapping("/jobs/search") public List<JobSummary> searchJobs(@RequestParam(required=false) String status,
            @RequestParam(required=false) String group, @RequestParam(required=false) String source,
            @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") int offset) {
        if (limit < 1 || limit > 500 || offset < 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid pagination");
        StringBuilder sql = new StringBuilder("select j.id,j.message_id,j.rule_id,j.rule_version,j.execution_group,j.status,j.attempts,j.last_error,j.created_at,j.completed_at from community.execution_job j join community.message_record m on m.id=j.message_id where 1=1");
        var parameters = new java.util.ArrayList<Object>();
        if (status != null && !status.isBlank()) { sql.append(" and j.status=?"); parameters.add(status); }
        if (group != null && !group.isBlank()) { sql.append(" and j.execution_group=?"); parameters.add(group); }
        if (source != null && !source.isBlank()) { sql.append(" and m.source_id=?"); parameters.add(source); }
        sql.append(" order by j.created_at desc,j.id desc limit ? offset ?"); parameters.add(limit); parameters.add(offset);
        return jdbc.query(sql.toString(), (rs,row) -> new JobSummary(UUID.fromString(rs.getString(1)),UUID.fromString(rs.getString(2)),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8),rs.getObject(9,OffsetDateTime.class),rs.getObject(10,OffsetDateTime.class)), parameters.toArray());
    }
    @GetMapping("/metrics/summary") public Map<String,Object> summary() {
        Integer messages=jdbc.queryForObject("select count(*) from community.message_record",Integer.class);
        Map<String,Integer> jobs=new java.util.LinkedHashMap<>();
        jdbc.query("select status,count(*) from community.execution_job group by status", (org.springframework.jdbc.core.RowCallbackHandler) rs -> jobs.put(rs.getString(1),rs.getInt(2)));
        return Map.of("messages",messages,"jobsByStatus",jobs);
    }
    @PostMapping("/jobs/{id}/retry") public ResponseEntity<?> retry(@PathVariable UUID id) {
        int changed = jdbc.update("update community.execution_job set status='READY', next_attempt_at=now(), claimed_by=null, lease_until=null, last_error=null, completed_at=null where id=? and status in ('DELIVERED','UNCERTAIN')", id);
        if (changed == 0) return ResponseEntity.status(409).body(Map.of("error", "job_cannot_be_retried", "message", "Only delivered or uncertain jobs can be retried."));
        return ResponseEntity.accepted().body(Map.of("id", id, "status", "READY", "notice", "Delivery retry can create a duplicate at the recipient."));
    }
    @Transactional
    @PostMapping("/messages/{id}/reprocess") public ResponseEntity<?> reprocess(@PathVariable UUID id) {
        var records = jdbc.query("select source_id,original_name from community.message_record where id=? for update", (rs,row) -> new ReprocessMessage(rs.getString(1),rs.getString(2)), id);
        if (records.isEmpty()) return ResponseEntity.notFound().build();
        var configurations = jdbc.query("select version,document_json::text from community.config_bundle order by version desc limit 1", (rs,row) -> new Configuration(rs.getInt(1),rs.getString(2)));
        if (configurations.isEmpty()) return ResponseEntity.status(409).body(Map.of("error", "no_published_configuration"));
        Configuration config = configurations.get(0); ReprocessMessage message = records.get(0);
        JsonNode document;
        try { document = new ObjectMapper().readTree(config.json()); }
        catch (java.io.IOException error) { throw new IllegalStateException("Stored configuration is invalid", error); }
        JsonNode source = null;
        for (JsonNode item : document.path("sources")) if (message.sourceId().equals(item.path("id").asText())) { source=item; break; }
        if (source == null) return ResponseEntity.status(409).body(Map.of("error", "source_not_in_current_configuration"));
        boolean first = "FIRST_MATCHING".equals(source.path("ruleSelection").asText());
        int created = 0;
        for (JsonNode rule : document.path("rules")) {
            boolean referenced = false; for (JsonNode candidate : rule.path("sourceIds")) if (message.sourceId().equals(candidate.asText())) referenced = true;
            String condition = rule.path("condition").path("fileNameEquals").asText();
            if (!referenced || !rule.path("enabled").asBoolean(true) || (!condition.isBlank() && !condition.equals(message.originalName()))) continue;
            String group = rule.path("executionGroup").asText("default"); if (group.isBlank()) group="default";
            jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,?,?,?, 'READY')", UUID.randomUUID(), id, rule.path("id").asText(), config.version(), group);
            created++; if (first) break;
        }
        if (created == 0) return ResponseEntity.status(409).body(Map.of("error", "no_matching_current_rules"));
        return ResponseEntity.accepted().body(Map.of("messageId", id, "createdJobs", created, "configurationVersion",config.version(), "notice", "Reprocessing uses the current configuration and may create duplicate deliveries."));
    }
    @Transactional
    @PutMapping("/messages/{id}/retention") public ResponseEntity<?> retention(@PathVariable UUID id, @RequestBody RetentionRequest request) {
        int changed = jdbc.update("update community.message_record set retain_until=? where id=?", request.retainUntil(), id);
        return changed == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }
    @Transactional
    @DeleteMapping("/messages/{id}") public ResponseEntity<?> deleteMessage(@PathVariable UUID id) {
        jdbc.execute("select pg_advisory_xact_lock(8273643)");
        var locked = jdbc.query("select source_id,checksum,coalesce(retain_until > now(), false) from community.message_record where id=? for update", (rs,row) -> new RetentionState(rs.getString(1),rs.getString(2),rs.getBoolean(3)), id);
        if (locked.isEmpty()) return ResponseEntity.notFound().build();
        RetentionState message = locked.get(0);
        if (message.retained()) return ResponseEntity.status(409).body(Map.of("error", "retention_active"));
        var statuses = jdbc.query("select status from community.execution_job where message_id=? for update", (rs,row) -> rs.getString(1), id);
        if (statuses.stream().anyMatch(status -> !"DELIVERED".equals(status))) return ResponseEntity.status(409).body(Map.of("error", "jobs_active_or_uncertain"));
        jdbc.update("""
                insert into community.message_deletion_audit(message_id,source_id,checksum,message_snapshot,job_history)
                select ?,?,?,(select to_jsonb(m) from community.message_record m where m.id=?),coalesce(jsonb_agg(jsonb_build_object('job',to_jsonb(j),
                    'events',coalesce((select jsonb_agg(to_jsonb(e) order by e.id) from community.job_event e where e.job_id=j.id),'[]'::jsonb)))
                    filter (where j.id is not null),'[]'::jsonb)
                from community.execution_job j where j.message_id=?
                """,id,message.sourceId(),message.checksum(),id,id);
        jdbc.update("delete from community.job_event where job_id in (select id from community.execution_job where message_id=?)",id);
        jdbc.update("delete from community.execution_job where message_id=?",id);
        jdbc.update("delete from community.message_record where id=?",id);
        // A storage key may alias a different checksum. Restart every grace period
        // whenever a reference disappears, even if cleanup never observed it.
        jdbc.update("delete from community.orphan_candidate");
        return ResponseEntity.accepted().body(Map.of("id",id,"notice","Metadata removed; unreferenced content is removed by delayed orphan cleanup."));
    }
    @GetMapping("/messages/{id}/integrity") public ResponseEntity<?> integrity(@PathVariable UUID id) {
        var records = jdbc.query("select checksum,storage_key from community.message_record where id=?", (rs,row)->new MessageStorage(rs.getString(1),rs.getString(2)), id);
        if (records.isEmpty()) return ResponseEntity.notFound().build();
        MessageStorage record = records.get(0);
        try { String actual=sha256(Path.of(record.storageKey())); return ResponseEntity.ok(Map.of("id",id,"valid",record.checksum().equals(actual),"checksum",record.checksum())); }
        catch (Exception e) { return ResponseEntity.status(503).body(Map.of("id",id,"valid",false,"error","original_unavailable")); }
    }
    @GetMapping("/messages/{id}/original") public ResponseEntity<Resource> original(@PathVariable UUID id) {
        var records = jdbc.query("select checksum,storage_key from community.message_record where id=?", (rs,row)->new MessageStorage(rs.getString(1),rs.getString(2)), id);
        if (records.isEmpty()) return ResponseEntity.notFound().build();
        Path path = Path.of(records.get(0).storageKey());
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return ResponseEntity.status(503).build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + id + ".bin\"")
                .body(new FileSystemResource(path));
    }
    public record MessageSummary(UUID id, String sourceId, String checksum, String originalName, String status, OffsetDateTime receivedAt, OffsetDateTime retainUntil) {}
    public record JobSummary(UUID id, UUID messageId, String ruleId, int ruleVersion, String executionGroup, String status, int attempts, String lastError, OffsetDateTime createdAt, OffsetDateTime completedAt) {}
    public record JobEvent(String status, int attempt, String workerId, String error, OffsetDateTime occurredAt) {}
    public record RetentionRequest(OffsetDateTime retainUntil) {}
    private record RetentionState(String sourceId, String checksum, boolean retained) {}
    private record ReprocessMessage(String sourceId, String originalName) {}
    private record Configuration(int version, String json) {}
    private record MessageStorage(String checksum,String storageKey) {}
    private static String sha256(Path file) throws Exception { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try (var input=Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest)); } return HexFormat.of().formatHex(digest.digest()); }
}
