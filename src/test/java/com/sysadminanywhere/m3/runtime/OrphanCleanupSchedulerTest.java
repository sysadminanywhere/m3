package com.sysadminanywhere.m3.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrphanCleanupSchedulerTest {
    @Test void deletesOnlyOldUnreferencedManagedObjects() throws Exception {
        var root = Files.createTempDirectory("m3-orphan-test-");
        var folder = Files.createDirectory(root.resolve("aa"));
        String orphan = "aa" + "0".repeat(62), referenced = "aa" + "1".repeat(62), fresh = "aa" + "2".repeat(62);
        var old = FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS));
        for (String name : new String[]{orphan, referenced, fresh, "user-file"}) Files.writeString(folder.resolve(name), "data");
        Files.writeString(folder.resolve(".ingest-123.part"), "unverified ownership");
        Files.setLastModifiedTime(folder.resolve(".ingest-123.part"), old);
        Files.setLastModifiedTime(folder.resolve(orphan), old);
        Files.setLastModifiedTime(folder.resolve(referenced), old);
        Files.setLastModifiedTime(folder.resolve("user-file"), old);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(orphan))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(referenced))).thenReturn(true);
        when(jdbc.queryForObject(contains("first_seen"), eq(Boolean.class), eq(168L), eq(orphan))).thenReturn(true);
        new OrphanCleanupScheduler(jdbc, root.toString(), 168).cleanup();
        assertFalse(Files.exists(folder.resolve(orphan)));
        assertTrue(Files.exists(folder.resolve(referenced)));
        assertTrue(Files.exists(folder.resolve(fresh)));
        assertTrue(Files.exists(folder.resolve("user-file")));
        assertTrue(Files.exists(folder.resolve(".ingest-123.part")));
    }
}
