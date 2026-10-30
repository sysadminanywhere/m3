package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DirectoryIngestScheduler {
    private static final Logger log = LoggerFactory.getLogger(DirectoryIngestScheduler.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path storageRoot;
    private final long stabilityWindowMs;
    private final String workerId;
    private final Map<Path, FileObservation> observations = new ConcurrentHashMap<>();

    public DirectoryIngestScheduler(JdbcTemplate jdbc, ObjectMapper mapper,
                                    @Value("${M3_STORAGE_ROOT:${m3.storage.root:./data/messages}}") String storageRoot,
                                    @Value("${m3.ingest.stability-window-ms:1000}") long stabilityWindowMs,
                                    @Value("${m3.worker.id:${HOSTNAME:community-local}}") String workerId) {
        this.jdbc = jdbc; this.mapper = mapper; this.storageRoot = Path.of(storageRoot);
        this.stabilityWindowMs = Math.max(0, stabilityWindowMs);
        this.workerId = workerId + "-" + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${m3.ingest.fixed-delay-ms:5000}")
    @Transactional
    public void poll() {
        jdbc.execute("select pg_advisory_xact_lock(8273643)");
        jdbc.query("select version, document_json from community.config_bundle order by version desc limit 1", rs -> {
            try { ingestConfiguredDirectories(mapper.readTree(rs.getString(2)), rs.getInt(1)); }
            catch (Exception error) { throw new IllegalStateException("Directory ingestion failed", error); }
        });
    }

    private void ingestConfiguredDirectories(JsonNode document, int configVersion) throws Exception {
        for (JsonNode source : document.path("sources")) {
            String sourceId = source.path("id").asText("");
            if (sourceId.isBlank() || !acquireSourceLease(sourceId)) continue;
            JsonNode connection = find(document.path("connections"), source.path("connectionId").asText(""));
            if (!"directory".equals(connection.path("type").asText())) continue;
            Path input = Path.of(connection.path("settings").path("path").asText());
            if (!Files.isDirectory(input)) continue;
            try (var files = Files.list(input)) { files.filter(Files::isRegularFile).filter(this::isStable).forEach(file -> register(document, configVersion, sourceId, file)); }
        }
    }

    private boolean acquireSourceLease(String sourceId) {
        try {
            String owner = jdbc.queryForObject("""
                    insert into community.source_lease(source_id, owner_id, lease_until)
                    values (?, ?, now() + interval '60 seconds')
                    on conflict (source_id) do update set owner_id=excluded.owner_id,
                        lease_until=excluded.lease_until, updated_at=now()
                    where community.source_lease.lease_until < now()
                       or community.source_lease.owner_id=excluded.owner_id
                    returning owner_id
                    """, String.class, sourceId, workerId);
            return workerId.equals(owner);
        } catch (org.springframework.dao.EmptyResultDataAccessException unavailable) {
            return false;
        }
    }

    private boolean isStable(Path file) {
        try {
            var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            long now = System.currentTimeMillis();
            FileObservation previous = observations.get(file);
            FileObservation current = new FileObservation(attributes.size(), attributes.lastModifiedTime().toMillis(), now);
            if (previous == null || previous.size() != current.size() || previous.modifiedAt() != current.modifiedAt()) observations.put(file, current);
            return isStableObservation(previous, current, now, stabilityWindowMs);
        } catch (Exception ignored) {
            observations.remove(file);
            return false;
        }
    }

    private void register(JsonNode document, int configVersion, String sourceId, Path file) {
        try {
            String checksum = sha256(file);
            Path target = storageRoot.resolve(checksum.substring(0, 2)).resolve(checksum);
            storeOriginal(file, target, checksum);
            UUID messageId = UUID.randomUUID();
            int accepted = jdbc.update("insert into community.message_record(id, source_id, checksum, original_name, storage_key, status) values (?, ?, ?, ?, ?, 'RECEIVED') on conflict (source_id, checksum) do nothing", messageId, sourceId, checksum, file.getFileName().toString(), target.toString());
            if (accepted == 1) {
                createJobs(document, configVersion, sourceId, messageId, firstMatchOnly(document, sourceId));
                jdbc.update("update community.message_record set status='NO_MATCH' where id=? and not exists (select 1 from community.execution_job where message_id=?)", messageId, messageId);
            }
        } catch (StorageFailure error) {
            log.error("Original storage failure: reason={} storageRoot={}; ingestion stopped", error.reason, storageRoot, error);
            throw error;
        } catch (Exception error) { throw new IllegalStateException("File registration failed: " + file, error); }
    }

    static void storeOriginal(Path file, Path target, String checksum) throws Exception {
        try {
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Path temporary = Files.createTempFile(target.getParent(), ".ingest-", ".part");
                try {
                    Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING);
                    if (!checksum.equals(sha256(temporary))) throw new IllegalStateException("Source changed during ingestion");
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                    catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
                    catch (java.nio.file.FileAlreadyExistsException concurrent) { /* Another source stored the same content. */ }
                } catch (Exception error) {
                    try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                    throw error;
                }
                Files.deleteIfExists(temporary);
            }
            if (!checksum.equals(sha256(target))) throw new IllegalStateException("Stored content checksum mismatch");
        } catch (IOException error) {
            throw new StorageFailure(storageReason(error), error);
        }
    }

    static String storageReason(IOException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String detail = cause instanceof FileSystemException fs ? fs.getReason() : cause.getMessage();
            if (detail != null) {
                String normalized = detail.toLowerCase(Locale.ROOT);
                if (normalized.contains("no space left") || normalized.contains("disk full")
                        || normalized.contains("not enough space") || normalized.contains("quota exceeded")) return "storage_full";
            }
        }
        return "storage_unavailable";
    }

    static final class StorageFailure extends IllegalStateException {
        final String reason;
        StorageFailure(String reason, IOException cause) { super("Original storage failure: " + reason, cause); this.reason = reason; }
    }

    private void createJobs(JsonNode document, int configVersion, String sourceId, UUID messageId, boolean firstMatchOnly) {
        for (JsonNode rule : document.path("rules")) {
            if (!rule.path("enabled").asBoolean(true) || !references(rule.path("sourceIds"), sourceId) || !matches(rule, messageId)) continue;
            String ruleId = rule.path("id").asText("");
            if (ruleId.isBlank()) continue;
            String executionGroup = rule.path("executionGroup").asText("default");
            if (executionGroup.isBlank()) executionGroup = "default";
            jdbc.update("insert into community.execution_job(id, message_id, rule_id, rule_version, execution_group, status) values (?, ?, ?, ?, ?, 'READY')", UUID.randomUUID(), messageId, ruleId, configVersion, executionGroup);
            if (firstMatchOnly) return;
        }
    }

    private static boolean firstMatchOnly(JsonNode document, String sourceId) {
        for (JsonNode source : document.path("sources")) if (sourceId.equals(source.path("id").asText())) return "FIRST_MATCHING".equals(source.path("ruleSelection").asText());
        return false;
    }

    private static JsonNode find(JsonNode array, String id) { for (JsonNode n : array) if (id.equals(n.path("id").asText())) return n; return com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }
    private static boolean references(JsonNode array, String id) { for (JsonNode n : array) if (id.equals(n.asText())) return true; return false; }
    private boolean matches(JsonNode rule, UUID messageId) {
        String expected = rule.path("condition").path("fileNameEquals").asText();
        if (expected.isBlank()) return true;
        String name = jdbc.queryForObject("select original_name from community.message_record where id=?", String.class, messageId);
        return expected.equals(name);
    }
    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); }
        return HexFormat.of().formatHex(digest.digest());
    }

    static boolean isStableObservation(FileObservation previous, FileObservation current, long now, long windowMs) {
        return previous != null
                && previous.size() == current.size()
                && previous.modifiedAt() == current.modifiedAt()
                && now - previous.observedAt() >= windowMs;
    }

    record FileObservation(long size, long modifiedAt, long observedAt) {}
}
