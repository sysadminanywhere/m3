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
        var scheduler = new DirectoryIngestScheduler(jdbc, new ObjectMapper(), blockedRoot.toString(), 0, "test");
        Method register = DirectoryIngestScheduler.class.getDeclaredMethod("register", com.fasterxml.jackson.databind.JsonNode.class, int.class, String.class, Path.class);
        register.setAccessible(true);

        var failure = assertThrows(InvocationTargetException.class,
                () -> register.invoke(scheduler, new ObjectMapper().readTree("{}"), 1, "source", input));
        var storage = assertInstanceOf(DirectoryIngestScheduler.StorageFailure.class, failure.getCause());
        assertEquals("storage_unavailable", storage.reason);
        verifyNoInteractions(jdbc);
        assertEquals("original", Files.readString(input));
    }

    @Test void identifiesFullAndUnavailableStorage() {
        assertEquals("storage_full", DirectoryIngestScheduler.storageReason(new FileSystemException("file", null, "No space left on device")));
        assertEquals("storage_full", DirectoryIngestScheduler.storageReason(new FileSystemException("file", null, "Disk quota exceeded")));
        assertEquals("storage_unavailable", DirectoryIngestScheduler.storageReason(new FileSystemException("file", null, "Permission denied")));
    }

    @Test void storesExactOriginalAndCleansTemporaryFile(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("input");
        byte[] bytes = {0, 1, 2, (byte) 255};
        Files.write(input, bytes);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path target = dir.resolve("storage").resolve(checksum);
        DirectoryIngestScheduler.storeOriginal(input, target, checksum);
        assertArrayEquals(bytes, Files.readAllBytes(target));
        try (var files = Files.list(target.getParent())) { assertEquals(1, files.count()); }
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
            tx.executeWithoutResult(status -> scheduler.poll()); // Observe the source file.

            try (var output = Files.newOutputStream(filler)) {
                byte[] block = new byte[8192];
                IOException full = assertThrows(IOException.class, () -> {
                    while (true) output.write(block);
                });
                assertEquals("storage_full", DirectoryIngestScheduler.storageReason(full));
            }
            Exception failure = assertThrows(Exception.class, () -> tx.executeWithoutResult(status -> scheduler.poll()));
            Throwable cause = failure;
            while (cause != null && !(cause instanceof DirectoryIngestScheduler.StorageFailure)) cause = cause.getCause();
            assertNotNull(cause, "Expected the actual storage failure");
            assertEquals("storage_full", ((DirectoryIngestScheduler.StorageFailure) cause).reason);
            assertEquals(0, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
            assertArrayEquals(content, Files.readAllBytes(input));
            try (var files = Files.list(target.getParent())) { assertEquals(0, files.count()); }

            Files.delete(filler);
            tx.executeWithoutResult(status -> scheduler.poll());
            assertArrayEquals(content, Files.readAllBytes(target));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
            tx.executeWithoutResult(status -> scheduler.poll());
            assertEquals(1, jdbc.queryForObject("select count(*) from community.message_record", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from community.execution_job", Integer.class));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
