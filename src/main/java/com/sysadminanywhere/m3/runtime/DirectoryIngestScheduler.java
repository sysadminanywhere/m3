package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class DirectoryIngestScheduler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path storageRoot;

    public DirectoryIngestScheduler(JdbcTemplate jdbc, ObjectMapper mapper,
                                    @Value("${M3_STORAGE_ROOT:${m3.storage.root:./data/messages}}") String storageRoot) {
        this.jdbc = jdbc; this.mapper = mapper; this.storageRoot = Path.of(storageRoot);
    }

    @Scheduled(fixedDelayString = "${m3.ingest.fixed-delay-ms:5000}")
    @Transactional
    public void poll() {
        jdbc.query("select version, document_json from community.config_bundle order by version desc limit 1", rs -> {
            try { ingestConfiguredDirectories(mapper.readTree(rs.getString(2)), rs.getInt(1)); }
            catch (Exception ignored) { /* configuration validation reports errors; one bad source must not stop peers */ }
        });
    }

    private void ingestConfiguredDirectories(JsonNode document, int configVersion) throws Exception {
        for (JsonNode source : document.path("sources")) {
            String sourceId = source.path("id").asText("");
            JsonNode connection = find(document.path("connections"), source.path("connectionId").asText(""));
            if (!"directory".equals(connection.path("type").asText())) continue;
            Path input = Path.of(connection.path("settings").path("path").asText());
            if (!Files.isDirectory(input)) continue;
            try (var files = Files.list(input)) { files.filter(Files::isRegularFile).forEach(file -> register(document, configVersion, sourceId, file)); }
        }
    }

    private void register(JsonNode document, int configVersion, String sourceId, Path file) {
        try {
            String checksum = sha256(file);
            Path target = storageRoot.resolve(checksum.substring(0, 2)).resolve(checksum);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
            UUID messageId = UUID.randomUUID();
            int accepted = jdbc.update("insert into community.message_record(id, source_id, checksum, original_name, storage_key, status) values (?, ?, ?, ?, ?, 'RECEIVED') on conflict (source_id, checksum) do nothing", messageId, sourceId, checksum, file.getFileName().toString(), target.toString());
            if (accepted == 1) createJobs(document, configVersion, sourceId, messageId, firstMatchOnly(document, sourceId));
        } catch (Exception ignored) { }
    }

    private void createJobs(JsonNode document, int configVersion, String sourceId, UUID messageId, boolean firstMatchOnly) {
        for (JsonNode rule : document.path("rules")) {
            if (!rule.path("enabled").asBoolean(true) || !references(rule.path("sourceIds"), sourceId) || !matches(rule, messageId)) continue;
            String ruleId = rule.path("id").asText("");
            if (ruleId.isBlank()) continue;
            jdbc.update("insert into community.execution_job(id, message_id, rule_id, rule_version, status) values (?, ?, ?, ?, 'READY')", UUID.randomUUID(), messageId, ruleId, configVersion);
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
}
