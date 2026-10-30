package com.sysadminanywhere.m3.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Only unreferenced content-addressed objects are eligible. */
@Component
public class OrphanCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrphanCleanupScheduler.class);
    private final JdbcTemplate jdbc;
    private final Path root;
    private final long retentionHours;
    public OrphanCleanupScheduler(JdbcTemplate jdbc, @Value("${M3_STORAGE_ROOT:${m3.storage.root:./data/messages}}") String root,
                                  @Value("${m3.storage.orphan-retention-hours:168}") long retentionHours) {
        this.jdbc = jdbc; this.root = Path.of(root); this.retentionHours = Math.max(168, retentionHours);
    }
    @Scheduled(fixedDelayString = "${m3.storage.orphan-cleanup-delay-ms:3600000}")
    @Transactional
    public void cleanup() {
        jdbc.execute("select pg_advisory_xact_lock(8273643)");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        cleanStaging();
        try (var directories = Files.list(root)) {
            directories.filter(p -> p.getFileName().toString().matches("[0-9a-f]{2}") && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(this::cleanDirectory);
        } catch (java.io.IOException error) { log.warn("Cannot scan orphan storage", error); }
    }
    private void cleanStaging() {
        Path stagingRoot = root.resolve(".m3-ingest-tmp");
        if (!Files.isDirectory(stagingRoot, LinkOption.NOFOLLOW_LINKS)) return;
        var old = jdbc.query("select id,source_id,owner_id from community.ingest_staging where created_at < now() - (? * interval '1 hour')", (rs,row) -> new Staging(java.util.UUID.fromString(rs.getString(1)),rs.getString(2),rs.getString(3)), retentionHours);
        for (Staging item : old) {
            var leases = jdbc.query("select owner_id,lease_until > clock_timestamp() from community.source_lease where source_id=? for update", (rs,row)->new Lease(rs.getString(1),rs.getBoolean(2)), item.sourceId());
            if (!leases.isEmpty() && leases.get(0).active() && item.ownerId().equals(leases.get(0).owner())) continue;
            Path file = stagingRoot.resolve(item.id() + ".part");
            try {
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    Files.delete(file);
                }
                jdbc.update("delete from community.ingest_staging where id=?", item.id());
            } catch (java.io.IOException error) { log.warn("Cannot remove stale ingest staging file {}", file, error); }
        }
    }
    private record Staging(java.util.UUID id, String sourceId, String ownerId) {}
    private record Lease(String owner, boolean active) {}
    private void cleanDirectory(Path directory) {
        try (var files = Files.list(directory)) {
            files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(file -> {
                String name = file.getFileName().toString();
                boolean content = name.matches("[0-9a-f]{64}") && name.startsWith(directory.getFileName().toString());
                if (!content) return;
                try {
                    if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from community.message_record where checksum=?)", Boolean.class, name)) || referencedByStorageKey(file)) {
                        jdbc.update("delete from community.orphan_candidate where checksum=?", name); return;
                    }
                    jdbc.update("insert into community.orphan_candidate(checksum) values (?) on conflict do nothing", name);
                    Boolean expired = jdbc.queryForObject("select first_seen < now() - (? * interval '1 hour') from community.orphan_candidate where checksum=?", Boolean.class, retentionHours, name);
                    if (!Boolean.TRUE.equals(expired)) return;
                    Files.deleteIfExists(file);
                    jdbc.update("delete from community.orphan_candidate where checksum=?", name);
                } catch (java.io.IOException error) { log.warn("Cannot remove orphan object {}", file, error); }
            });
        } catch (java.io.IOException error) { log.warn("Cannot scan orphan directory {}", directory, error); }
    }
    private boolean referencedByStorageKey(Path file) throws java.io.IOException {
        for (String key : jdbc.queryForList("select storage_key from community.message_record", String.class)) {
            try {
                Path referenced = Path.of(key);
                if (referenced.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())
                        || (Files.exists(referenced) && Files.isSameFile(referenced, file))) return true;
            } catch (java.nio.file.InvalidPathException error) {
                throw new java.io.IOException("Invalid storage key; refusing orphan removal", error);
            }
        }
        return false;
    }
}
