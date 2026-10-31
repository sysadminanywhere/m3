package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectoryIngestSchedulerTest {
    @Test
    @ExtendWith(DisposablePostgres.class)
    @EnabledIfEnvironmentVariable(named = "M3_TEST_POSTGRES_URL", matches = ".+")
    void forgetsFilesRemovedFromTheInputDirectory(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        Path input = Files.createDirectory(dir.resolve("input")); Path file = Files.writeString(input.resolve("short-lived.txt"), "content");
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper(); var document = mapper.createObjectNode();
        document.putArray("connections").addObject().put("id", "c").put("type", "directory").putObject("settings").put("path", input.toString());
        document.putArray("sources").addObject().put("id", "s").put("connectionId", "c");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", document.toString());
        var scheduler = new DirectoryIngestScheduler(jdbc, mapper, dir.resolve("store").toString(), 1000, "test");
        var field = DirectoryIngestScheduler.class.getDeclaredField("observations"); field.setAccessible(true);
        var observations = (java.util.Map<?,?>) field.get(scheduler);
        scheduler.poll(); assertEquals(1, observations.size());
        Files.delete(file); scheduler.poll(); assertTrue(observations.isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
    }
    @Test
    @ExtendWith(DisposablePostgres.class)
    @EnabledIfEnvironmentVariable(named = "M3_TEST_POSTGRES_URL", matches = ".+")
    void ingestionSelectsAllFirstAndRetainsUnmatchedOriginal(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        var document = mapper.createObjectNode(); var connections = document.putArray("connections"); var sources = document.putArray("sources");
        for (String id : new String[]{"all", "first", "none"}) {
            Path input = Files.createDirectory(dir.resolve(id)); Files.writeString(input.resolve("match.txt"), "payload-" + id);
            connections.addObject().put("id", id).put("type", "directory").putObject("settings").put("path", input.toString());
            sources.addObject().put("id", id).put("connectionId", id).put("ruleSelection", id.equals("first") ? "FIRST_MATCHING" : "ALL_MATCHING");
        }
        var rules = document.putArray("rules");
        var firstRule = rules.addObject().put("id", "a").put("recipientId", "out"); firstRule.putArray("sourceIds").add("all").add("first"); firstRule.putObject("condition").put("fileNameEquals", "match.txt");
        var secondRule = rules.addObject().put("id", "b").put("recipientId", "out"); secondRule.putArray("sourceIds").add("all").add("first");
        var unmatchedRule = rules.addObject().put("id", "c").put("recipientId", "out"); unmatchedRule.putArray("sourceIds").add("none"); unmatchedRule.putObject("condition").put("fileNameEquals", "other.txt");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", document.toString());
        var scheduler = new DirectoryIngestScheduler(jdbc, mapper, dir.resolve("store").toString(), 0, "test");
        scheduler.poll(); scheduler.poll();
        assertEquals(2, jdbc.queryForObject("select count(*) from community.execution_job j join community.message_record m on m.id=j.message_id where m.source_id='all'", Integer.class));
        assertEquals("a", jdbc.queryForObject("select j.rule_id from community.execution_job j join community.message_record m on m.id=j.message_id where m.source_id='first'", String.class));
        assertEquals("NO_MATCH", jdbc.queryForObject("select status from community.message_record where source_id='none'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from community.execution_job j join community.message_record m on m.id=j.message_id where m.source_id='none'", Integer.class));
        for (String id : new String[]{"all", "first", "none"}) {
            String key = jdbc.queryForObject("select storage_key from community.message_record where source_id=?", String.class, id);
            assertEquals("payload-" + id, Files.readString(Path.of(key)));
        }
    }
    @Test void recognizesSourceReference() throws Exception {
        Method method = DirectoryIngestScheduler.class.getDeclaredMethod("references", com.fasterxml.jackson.databind.JsonNode.class, String.class);
        method.setAccessible(true);
        var sources = new ObjectMapper().readTree("[\"one\",\"two\"]");
        assertTrue((boolean) method.invoke(null, sources, "two"));
        assertFalse((boolean) method.invoke(null, sources, "three"));
    }

    @Test void stableObservationRequiresAnUnchangedFileWindow() throws Exception {
        var first = new DirectoryIngestScheduler.FileObservation(10, 100, 1_000);
        var unchanged = new DirectoryIngestScheduler.FileObservation(10, 100, 2_100);
        var changed = new DirectoryIngestScheduler.FileObservation(11, 100, 2_100);

        assertFalse(DirectoryIngestScheduler.isStableObservation(null, first, 2_000, 1_000));
        assertTrue(DirectoryIngestScheduler.isStableObservation(first, unchanged, 2_100, 1_000));
        assertFalse(DirectoryIngestScheduler.isStableObservation(first, changed, 2_100, 1_000));
    }
    @Test void storageFailureStopsRegistrationBeforeDatabaseWrites(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("input.txt");
        Files.writeString(input, "original");
        Path blockedRoot = dir.resolve("not-a-directory");
        Files.writeString(blockedRoot, "blocked");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("insert into community.ingest_staging"), any(), any(), any())).thenReturn(1);
        var scheduler = new DirectoryIngestScheduler(jdbc, new ObjectMapper(), blockedRoot.toString(), 0, "test");
        Method register = DirectoryIngestScheduler.class.getDeclaredMethod("register", com.fasterxml.jackson.databind.JsonNode.class, int.class, String.class, Path.class, java.util.concurrent.atomic.AtomicBoolean.class);
        register.setAccessible(true);

        var failure = assertThrows(InvocationTargetException.class,
                () -> register.invoke(scheduler, new ObjectMapper().readTree("{}"), 1, "source", input, new java.util.concurrent.atomic.AtomicBoolean()));
        var storage = assertInstanceOf(DirectoryIngestScheduler.StorageFailure.class, failure.getCause());
        assertEquals("storage_unavailable", storage.reason);
        verify(jdbc, never()).update(startsWith("insert into community.message_record"), any(), any(), any(), any(), any());
        assertEquals("original", Files.readString(input));
    }

    @Test void identifiesFullAndUnavailableStorage() {
        assertEquals("storage_full", DirectoryIngestScheduler.storageReason(new FileSystemException("file", null, "No space left on device")));
        assertEquals("storage_full", DirectoryIngestScheduler.storageReason(new FileSystemException("file", null, "Disk quota exceeded")));
        assertEquals("storage_unavailable", DirectoryIngestScheduler.storageReason(new FileSystemException("file", null, "Permission denied")));
    }

    @Test
    @ExtendWith(DisposablePostgres.class)
    @EnabledIfEnvironmentVariable(named = "M3_TEST_POSTGRES_URL", matches = ".+")
    void copyAndHeartbeatRunOutsideLockAndLostOwnerCannotPublish(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        Path input = Files.createDirectory(dir.resolve("input"));
        Path original = input.resolve("user.txt");
        Files.writeString(original, "user content");
        Path root = dir.resolve("store");
        var jdbc = new JdbcTemplate(ds);
        var mapper = new ObjectMapper();
        var doc = mapper.createObjectNode();
        doc.putArray("connections").addObject().put("id", "c").put("type", "directory").putObject("settings").put("path", input.toString());
        doc.putArray("sources").addObject().put("id", "s").put("connectionId", "c");
        doc.putArray("rules").addObject().put("id", "r").putArray("sourceIds").add("s");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", doc.toString());
        var first = new DirectoryIngestScheduler(jdbc, mapper, root.toString(), 0, "first");
        first.poll();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var connection = ds.getConnection()) {
            connection.setAutoCommit(false);
            try (var stmt = connection.createStatement()) { stmt.execute("select pg_advisory_xact_lock(8273643)"); }
            var future = pool.submit(first::poll);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            Path tempDir = root.resolve(".m3-ingest-tmp");
            while ((!Files.isDirectory(tempDir) || isEmpty(tempDir)) && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(Files.isDirectory(tempDir) && !isEmpty(tempDir), "copy should finish while lock is held");
            var before = jdbc.queryForObject("select updated_at from community.source_lease where source_id='s'", java.time.OffsetDateTime.class);
            while (jdbc.queryForObject("select updated_at from community.source_lease where source_id='s'", java.time.OffsetDateTime.class).equals(before) && System.nanoTime() < deadline) Thread.sleep(100);
            assertNotEquals(before, jdbc.queryForObject("select updated_at from community.source_lease where source_id='s'", java.time.OffsetDateTime.class), "heartbeat commits while publication waits");
            jdbc.update("update community.source_lease set owner_id='successor', lease_until=clock_timestamp()+interval '60 seconds' where source_id='s'");
            connection.commit();
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
            assertEquals("user content", Files.readString(original));
            assertTrue(isEmpty(tempDir));
            jdbc.update("update community.source_lease set lease_until=clock_timestamp()-interval '1 second' where source_id='s'");
            var successor = new DirectoryIngestScheduler(jdbc, mapper, root.toString(), 0, "successor");
            successor.poll(); successor.poll();
            assertEquals(1, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
            assertEquals("user content", Files.readString(Path.of(jdbc.queryForObject("select storage_key from community.message_record", String.class))));
        } finally { pool.shutdownNow(); }
    }

    @Test
    @ExtendWith(DisposablePostgres.class)
    @EnabledIfEnvironmentVariable(named = "M3_TEST_POSTGRES_URL", matches = ".+")
    void failedRegistrationRollsBackAndCanRecover(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        Path input = Files.createDirectory(dir.resolve("input"));
        Files.writeString(input.resolve("original"), "content");
        Path root = dir.resolve("store");
        var control = new JdbcTemplate(ds);
        var jdbc = new JdbcTemplate(ds) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("insert into community.execution_job")) throw new IllegalStateException("simulated process failure");
                return super.update(sql, args);
            }
        };
        var mapper = new ObjectMapper();
        var doc = mapper.createObjectNode();
        doc.putArray("connections").addObject().put("id", "c").put("type", "directory").putObject("settings").put("path", input.toString());
        doc.putArray("sources").addObject().put("id", "s").put("connectionId", "c");
        doc.putArray("rules").addObject().put("id", "r").putArray("sourceIds").add("s");
        control.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", doc.toString());
        var failing = new DirectoryIngestScheduler(jdbc, mapper, root.toString(), 0, "failing");
        failing.poll();
        assertThrows(IllegalStateException.class, failing::poll);
        assertEquals(0, control.queryForObject("select count(*) from community.message_record", Integer.class));
        assertEquals(0, control.queryForObject("select count(*) from community.execution_job", Integer.class));
        assertEquals("content", Files.readString(input.resolve("original")));
        var cleanup = new OrphanCleanupScheduler(control, root.toString(), 168);
        new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(status -> cleanup.cleanup());
        assertEquals(1, control.queryForObject("select count(*) from community.orphan_candidate", Integer.class));
        control.update("update community.source_lease set lease_until=clock_timestamp()-interval '1 second'");
        var retry = new DirectoryIngestScheduler(control, mapper, root.toString(), 0, "retry");
        retry.poll(); retry.poll();
        assertEquals(1, control.queryForObject("select count(*) from community.message_record", Integer.class));
        assertEquals(1, control.queryForObject("select count(*) from community.execution_job", Integer.class));
        assertEquals("content", Files.readString(Path.of(control.queryForObject("select storage_key from community.message_record", String.class))));
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var files = Files.list(dir)) { return files.findAny().isEmpty(); }
    }

    @Test
    @ExtendWith(DisposablePostgres.class)
    @EnabledIfEnvironmentVariable(named = "M3_TEST_POSTGRES_URL", matches = ".+")
    void configChangeDuringCopyUsesNewVersionAndGroup(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        Path input=Files.createDirectory(dir.resolve("input")); Files.writeString(input.resolve("file"), "content");
        Path root=dir.resolve("store"); var jdbc=new JdbcTemplate(ds); var mapper=new ObjectMapper();
        var document=mapper.createObjectNode();
        document.putArray("connections").addObject().put("id","c").put("type","directory").putObject("settings").put("path",input.toString());
        document.putArray("sources").addObject().put("id","s").put("connectionId","c");
        document.putArray("rules").addObject().put("id","r").put("executionGroup","old").putArray("sourceIds").add("s");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))",document.toString());
        var scheduler=new DirectoryIngestScheduler(jdbc,mapper,root.toString(),0,"test"); scheduler.poll();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var connection=ds.getConnection()) {
            connection.setAutoCommit(false);
            try (var stmt=connection.createStatement()) { stmt.execute("select pg_advisory_xact_lock(8273643)"); }
            var future=pool.submit(scheduler::poll);
            Path temp=root.resolve(".m3-ingest-tmp"); long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while ((!Files.isDirectory(temp) || isEmpty(temp)) && System.nanoTime()<deadline) Thread.sleep(50);
            assertTrue(Files.isDirectory(temp) && !isEmpty(temp));
            ((com.fasterxml.jackson.databind.node.ObjectNode)document.path("rules").get(0)).put("executionGroup","new");
            jdbc.update("insert into community.config_bundle(version,document_json) values (2,cast(? as jsonb))",document.toString());
            connection.commit(); future.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0,jdbc.queryForObject("select count(*) from community.message_record",Integer.class));
            scheduler.poll();
            assertEquals("new",jdbc.queryForObject("select execution_group from community.execution_job",String.class));
            assertEquals(2,jdbc.queryForObject("select rule_version from community.execution_job",Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    @ExtendWith(DisposablePostgres.class)
    @EnabledIfEnvironmentVariable(named = "M3_TEST_INGEST_TMPFS", matches = ".+")
    void recoversAfterActualFullStorage(@TempDir Path inputDir, DriverManagerDataSource ds) throws Exception {
        Path mount = Path.of(System.getenv("M3_TEST_INGEST_TMPFS"));
        var store = Files.getFileStore(mount);
        assertEquals("tmpfs", store.type());
        assertTrue(store.getTotalSpace() <= 32L * 1024 * 1024, "Storage must be a small isolated tmpfs");
        Path root = Files.createTempDirectory(mount, "ingest-test-");
        Path filler = root.resolve("filler");
        try {
            byte[] content = new byte[2 * 1024 * 1024];
            new java.util.Random(42).nextBytes(content);
            Path input = inputDir.resolve("synthetic.bin");
            Files.write(input, content);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            Path target = root.resolve(checksum.substring(0, 2)).resolve(checksum);
            Files.createDirectories(target.getParent());

            var jdbc = new JdbcTemplate(ds);
            var mapper = new ObjectMapper();
            var document = mapper.createObjectNode();
            document.putArray("connections").addObject().put("id", "c").put("type", "directory")
                    .putObject("settings").put("path", inputDir.toString());
            document.putArray("sources").addObject().put("id", "s").put("connectionId", "c");
            document.putArray("rules").addObject().put("id", "r").putArray("sourceIds").add("s");
            jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", document.toString());
            var scheduler = new DirectoryIngestScheduler(jdbc, mapper, root.toString(), 0, "test");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            scheduler.poll(); // Observe the source file.

            try (var output = Files.newOutputStream(filler)) {
                byte[] block = new byte[8192];
                IOException full = assertThrows(IOException.class, () -> {
                    while (true) output.write(block);
                });
                assertEquals("storage_full", DirectoryIngestScheduler.storageReason(full));
            }
            Exception failure = assertThrows(Exception.class, () -> scheduler.poll());
            Throwable cause = failure;
            while (cause != null && !(cause instanceof DirectoryIngestScheduler.StorageFailure)) cause = cause.getCause();
            assertNotNull(cause, "Expected the actual storage failure");
            assertEquals("storage_full", ((DirectoryIngestScheduler.StorageFailure) cause).reason);
            assertEquals(0, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
            assertArrayEquals(content, Files.readAllBytes(input));
            try (var files = Files.list(target.getParent())) { assertEquals(0, files.count()); }

            Files.delete(filler);
            scheduler.poll();
            assertArrayEquals(content, Files.readAllBytes(target));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
            scheduler.poll();
            assertEquals(1, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
