package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import org.apache.sshd.sftp.client.SftpClient;

@Component
public class DirectoryIngestScheduler {
    private static final Logger log = LoggerFactory.getLogger(DirectoryIngestScheduler.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path storageRoot;
    private final long stabilityWindowMs;
    private final String workerId;
    private final Map<ObservationKey, FileObservation> observations = new ConcurrentHashMap<>();
    private final RuntimeSecretResolver secrets;
    private final OperationalAlerts alerts;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectoryIngestScheduler(JdbcTemplate jdbc, ObjectMapper mapper,
                                    @Value("${M3_STORAGE_ROOT:${m3.storage.root:./data/messages}}") String storageRoot,
                                    @Value("${m3.ingest.stability-window-ms:1000}") long stabilityWindowMs,
                                    @Value("${m3.worker.id:${HOSTNAME:community-local}}") String workerId,
                                    @org.springframework.context.annotation.Lazy RuntimeSecretResolver secrets,
                                    @org.springframework.context.annotation.Lazy OperationalAlerts alerts) {
        this.jdbc = jdbc; this.mapper = mapper; this.storageRoot = Path.of(storageRoot);
        this.stabilityWindowMs = Math.max(0, stabilityWindowMs);
        this.workerId = workerId + "-" + UUID.randomUUID();
        this.secrets=secrets;
        this.alerts=alerts;
    }
    public DirectoryIngestScheduler(JdbcTemplate jdbc,ObjectMapper mapper,String storageRoot,long stabilityWindowMs,String workerId) {
        this(jdbc,mapper,storageRoot,stabilityWindowMs,workerId,null,null);
    }
    public DirectoryIngestScheduler(JdbcTemplate jdbc,ObjectMapper mapper,String storageRoot,long stabilityWindowMs,String workerId,RuntimeSecretResolver secrets) {
        this(jdbc,mapper,storageRoot,stabilityWindowMs,workerId,secrets,null);
    }

    @Scheduled(fixedDelayString = "${m3.ingest.fixed-delay-ms:5000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void poll() {
        jdbc.query("select version, document_json from community.config_bundle order by version desc limit 1", rs -> {
            try { ingestConfiguredDirectories(mapper.readTree(rs.getString(2)), rs.getInt(1)); }
            catch (Exception error) { throw new IllegalStateException("Directory ingestion failed", error); }
        });
    }

    private void ingestConfiguredDirectories(JsonNode document, int configVersion) throws Exception {
        var observedFiles = new java.util.HashSet<ObservationKey>();
        for (JsonNode source : document.path("sources")) {
            String sourceId = source.path("id").asText("");
            if (sourceId.isBlank() || !acquireSourceLease(sourceId)) continue;
            var lost = new AtomicBoolean();
            var heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "m3-ingest-lease"); thread.setDaemon(true); return thread;
            });
            heartbeat.scheduleAtFixedRate(() -> {
                try { if (!renewSourceLease(sourceId)) lost.set(true); }
                catch (Exception error) { lost.set(true); log.warn("Source lease renewal failed: {}", sourceId, error); }
            }, 10, 10, TimeUnit.SECONDS);
            try {
                JsonNode connection = find(document.path("connections"), source.path("connectionId").asText(""));
                if ("sftp".equals(connection.path("type").asText())) {
                    ingestSftp(document,configVersion,sourceId,connection.path("settings"),lost,observedFiles);
                    continue;
                }
                if (!"directory".equals(connection.path("type").asText())) continue;
                Path input = Path.of(connection.path("settings").path("path").asText());
                if (!Files.isDirectory(input)) continue;
                try (var files = Files.list(input)) {
                    files.filter(Files::isRegularFile).forEach(file -> {
                        var key=new ObservationKey(configVersion,sourceId,file.toAbsolutePath().normalize().toString()); observedFiles.add(key);
                        if (!lost.get() && isStable(file,key)) register(document,configVersion,sourceId,file,lost);
                    });
                }
            } finally { heartbeat.shutdownNow(); }

        }
        observations.keySet().retainAll(observedFiles);
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

    private boolean renewSourceLease(String sourceId) {
        return jdbc.update("update community.source_lease set lease_until=clock_timestamp() + interval '60 seconds', updated_at=clock_timestamp() where source_id=? and owner_id=? and lease_until > clock_timestamp()", sourceId, workerId) == 1;
    }

    private void ingestSftp(JsonNode document,int version,String sourceId,JsonNode settings,AtomicBoolean lost,java.util.Set<ObservationKey> observed) {
        if (secrets==null) return;
        String directory=settings.path("path").asText();
        if (!directory.startsWith("/") || directory.contains("\\") || directory.contains("\u0000") || java.util.Arrays.asList(directory.split("/",-1)).contains("..")) return;
        try (var connection=SftpConnection.open(settings.path("host").asText(),settings.path("port").asInt(22),settings.path("username").asText(),
                settings.path("hostKeyFingerprint").asText(),settings.path("credentialRef").asText(),java.time.Duration.ofSeconds(30),secrets)) {
            SftpClient client=connection.client(); String prefix=directory.endsWith("/")?directory:directory+"/";
            for (var entry : client.readDir(directory)) {
                String name=entry.getFilename();
                if (lost.get()) return;
                if (name==null || name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains("\u0000") || (name.startsWith(".m3-") && name.endsWith(".part"))) continue;
                String remote=prefix+name; var attrs=client.lstat(remote);
                if (!stableAttributes(attrs)) continue;
                var key=new ObservationKey(version,sourceId,"sftp:"+remote); observed.add(key);
                long now=System.currentTimeMillis(); var current=new FileObservation(attrs.getSize(),attrs.getModifyTime().toMillis(),now);
                var previous=observations.get(key);
                if (previous==null || previous.size()!=current.size() || previous.modifiedAt()!=current.modifiedAt()) observations.put(key,current);
                if (!isStableObservation(previous,current,now,stabilityWindowMs)) continue;
                register(document,version,sourceId,name,lost,temporary -> {
                    java.io.InputStream input;
                    try { input=client.read(remote); } catch (IOException failure) { throw new RemoteSourceFailure(); }
                    try (var output=Files.newOutputStream(temporary)) {
                        byte[] buffer=new byte[65536];
                        while (true) {
                            int read; try { read=input.read(buffer); } catch (IOException failure) { throw new RemoteSourceFailure(); }
                            if (read<0) break; output.write(buffer,0,read);
                        }
                    } finally { try { input.close(); } catch (IOException ignored) { } }
                    SftpClient.Attributes after;
                    try { after=client.lstat(remote); } catch (IOException failure) { throw new RemoteSourceFailure(); }
                    if (!stableAttributes(after) || after.getSize()!=current.size() || after.getModifyTime().toMillis()!=current.modifiedAt()
                            || Files.size(temporary)!=current.size()) throw new RemoteSourceFailure();
                });
            }
        } catch (StorageFailure failure) { throw failure; }
        catch (Exception failure) { log.warn("SFTP source unavailable or changed: sourceId={}",sourceId); }
    }
    private static boolean stableAttributes(SftpClient.Attributes attrs) {
        return attrs!=null && attrs.isRegularFile() && !attrs.isSymbolicLink() && attrs.getFlags().contains(SftpClient.Attribute.Size)
                && attrs.getFlags().contains(SftpClient.Attribute.ModifyTime) && attrs.getModifyTime()!=null;
    }
    private static final class RemoteSourceFailure extends RuntimeException {}

    private boolean isStable(Path file, ObservationKey key) {
        try {
            var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            long now = System.currentTimeMillis();
            FileObservation previous = observations.get(key);
            FileObservation current = new FileObservation(attributes.size(), attributes.lastModifiedTime().toMillis(), now);
            if (previous == null || previous.size() != current.size() || previous.modifiedAt() != current.modifiedAt()) observations.put(key, current);
            return isStableObservation(previous, current, now, stabilityWindowMs);
        } catch (Exception ignored) {
            observations.remove(key);
            return false;
        }
    }

    @FunctionalInterface private interface CopySource { void copy(Path temporary) throws Exception; }
    private void register(JsonNode document,int version,String sourceId,Path file,AtomicBoolean lost) {
        register(document,version,sourceId,file.getFileName().toString(),lost,temporary -> {
            Files.copy(file,temporary); if (!sha256(temporary).equals(sha256(file))) throw new IOException("Source changed during ingestion");
        });
    }
    private void register(JsonNode document, int configVersion, String sourceId, String originalName, AtomicBoolean lost, CopySource copy) {
        Path temporary = null;
        UUID stagingId = UUID.randomUUID();
        boolean stagingRegistered = false;
        var registered=new AtomicBoolean();
        try {
            int reserved = jdbc.update("insert into community.ingest_staging(id,source_id,owner_id) select ?,source_id,owner_id from community.source_lease where source_id=? and owner_id=? and lease_until > clock_timestamp()", stagingId, sourceId, workerId);
            stagingRegistered = reserved == 1;
            if (!stagingRegistered || lost.get()) return;
            Path temporaryRoot = storageRoot.resolve(".m3-ingest-tmp");
            Files.createDirectories(temporaryRoot);
            temporary = temporaryRoot.resolve(stagingId + ".part");
            copy.copy(temporary);
            String checksum = sha256(temporary);
            Path target = storageRoot.resolve(checksum.substring(0, 2)).resolve(checksum);
            // Verify pre-existing content outside the global lock. The final transaction
            // rechecks its identity before accepting it.
            var existing = Files.exists(target) ? Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class) : null;
            if (existing != null && !checksum.equals(sha256(target))) throw new IllegalStateException("Stored content checksum mismatch");
            Path staged = temporary;
            var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> {
                jdbc.execute("select pg_advisory_xact_lock(8273643)");
                jdbc.execute("select pg_advisory_xact_lock(8273641)");
                Integer latest = jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class);
                if (latest == null || latest != configVersion) return; // Reobserve using the latest source and rule definitions.
                // This update locks the lease row until commit, fencing a successor.
                if (lost.get() || !renewSourceLease(sourceId)) return;
                try {
                    Files.createDirectories(target.getParent());
                    if (Files.exists(target)) {
                        var current = Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes.class);
                        if (existing == null || existing.size() != current.size() || !existing.lastModifiedTime().equals(current.lastModifiedTime()) || !java.util.Objects.equals(existing.fileKey(), current.fileKey())) throw new IllegalStateException("Stored content changed during ingestion; retry");
                    } else {
                        // Do not fall back to a copy: publication must be atomic.
                        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                } catch (IOException error) { throw new StorageFailure(storageReason(error), error); }
                UUID messageId = UUID.randomUUID();
                int accepted = jdbc.update("insert into community.message_record(id, source_id, checksum, original_name, storage_key, status) values (?, ?, ?, ?, ?, 'RECEIVED') on conflict (source_id, checksum) do nothing", messageId, sourceId, checksum, originalName, target.toString());
                if (accepted == 1) {
                    createJobs(document, configVersion, sourceId, messageId, firstMatchOnly(document, sourceId));
                    jdbc.update("update community.message_record set status='NO_MATCH' where id=? and not exists (select 1 from community.execution_job where message_id=?)", messageId, messageId);
                }
                if (lost.get() || !renewSourceLease(sourceId)) throw new IllegalStateException("Source lease expired during registration");
                registered.set(true);
            });
            if (registered.get()) storageAlert(sourceId,false);
        } catch (RemoteSourceFailure error) { throw error; }
        catch (StorageFailure error) {
            if ("storage_full".equals(error.reason)) storageAlert(sourceId,true);
            log.error("Original storage failure: reason={} storageRoot={}; ingestion stopped", error.reason, storageRoot, error);
            throw error;
        } catch (IOException error) {
            var storage=new StorageFailure(storageReason(error), error);
            if ("storage_full".equals(storage.reason)) storageAlert(sourceId,true);
            throw storage;
        } catch (Exception error) { throw new IllegalStateException("File registration failed", error); }
        finally {
            boolean removed = temporary == null;
            if (temporary != null) try { Files.deleteIfExists(temporary); removed = true; }
            catch (IOException error) { log.warn("Cannot remove ingest temporary file {}", temporary, error); }
            if (stagingRegistered && removed) try { jdbc.update("delete from community.ingest_staging where id=? and owner_id=?", stagingId, workerId); }
            catch (Exception error) { log.warn("Cannot clear ingest staging record {}", stagingId, error); }
        }
    }

    private void storageAlert(String sourceId,boolean active) {
        if (alerts==null) return;
        try { alerts.set("storage:"+sourceId,"STORAGE_FULL",active); }
        catch (RuntimeException failure) { log.warn("Could not persist storage alert state"); }
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
    private record ObservationKey(int version,String sourceId,String path) {}
}
