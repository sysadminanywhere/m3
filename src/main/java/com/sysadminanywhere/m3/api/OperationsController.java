package com.sysadminanywhere.m3.api;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

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
        return jdbc.query("select id, source_id, checksum, original_name, status, received_at from community.message_record order by received_at desc limit 200",
                (rs, row) -> new MessageSummary(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)));
    }
    @GetMapping("/jobs") public List<JobSummary> jobs() {
        return jdbc.query("select id, message_id, rule_id, rule_version, execution_group, status, attempts, last_error, created_at, completed_at from community.execution_job order by created_at desc limit 500",
                (rs, row) -> new JobSummary(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class)));
    }
    @GetMapping("/metrics/summary") public Map<String,Object> summary() {
        Integer messages=jdbc.queryForObject("select count(*) from community.message_record",Integer.class);
        Map<String,Integer> jobs=new java.util.LinkedHashMap<>();
        jdbc.query("select status,count(*) from community.execution_job group by status", (org.springframework.jdbc.core.RowCallbackHandler) rs -> jobs.put(rs.getString(1),rs.getInt(2)));
        return Map.of("messages",messages,"jobsByStatus",jobs);
    }
    @PostMapping("/jobs/{id}/retry") public ResponseEntity<?> retry(@PathVariable UUID id) {
        int changed = jdbc.update("update community.execution_job set status='READY', next_attempt_at=now(), claimed_by=null, lease_until=null, last_error=null where id=? and status in ('DELIVERED','READY')", id);
        if (changed == 0) return ResponseEntity.status(409).body(Map.of("error", "job_cannot_be_retried", "message", "A running job must finish or its lease must expire first."));
        return ResponseEntity.accepted().body(Map.of("id", id, "status", "READY", "notice", "Delivery retry can create a duplicate at the recipient."));
    }
    @PostMapping("/messages/{id}/reprocess") public ResponseEntity<?> reprocess(@PathVariable UUID id) {
        List<RuleVersion> rules = jdbc.query("select distinct rule_id, rule_version, execution_group from community.execution_job where message_id=?", (rs,row) -> new RuleVersion(rs.getString(1), rs.getInt(2), rs.getString(3)), id);
        if (rules.isEmpty()) return ResponseEntity.notFound().build();
        for (RuleVersion rule : rules) jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,?,?,?, 'READY')", UUID.randomUUID(), id, rule.id(), rule.version(), rule.group());
        return ResponseEntity.accepted().body(Map.of("messageId", id, "createdJobs", rules.size(), "notice", "Reprocessing creates new delivery jobs and can create duplicates."));
    }
    @GetMapping("/messages/{id}/integrity") public ResponseEntity<?> integrity(@PathVariable UUID id) {
        MessageStorage record = jdbc.queryForObject("select checksum,storage_key from community.message_record where id=?", (rs,row)->new MessageStorage(rs.getString(1),rs.getString(2)), id);
        try { String actual=sha256(Path.of(record.storageKey())); return ResponseEntity.ok(Map.of("id",id,"valid",record.checksum().equals(actual),"checksum",record.checksum())); }
        catch (Exception e) { return ResponseEntity.status(503).body(Map.of("id",id,"valid",false,"error","original_unavailable")); }
    }
    public record MessageSummary(UUID id, String sourceId, String checksum, String originalName, String status, OffsetDateTime receivedAt) {}
    public record JobSummary(UUID id, UUID messageId, String ruleId, int ruleVersion, String executionGroup, String status, int attempts, String lastError, OffsetDateTime createdAt, OffsetDateTime completedAt) {}
    private record RuleVersion(String id, int version, String group) {}
    private record MessageStorage(String checksum,String storageKey) {}
    private static String sha256(Path file) throws Exception { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try (var input=Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest)); } return HexFormat.of().formatHex(digest.digest()); }
}
