package com.sysadminanywhere.m3.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryDeliverySchedulerTest {
    @Test void keepsErrorBoundedForPersistence() {
        assertEquals(1000, DirectoryDeliveryScheduler.truncate("x".repeat(1200)).length());
        assertEquals("unknown failure", DirectoryDeliveryScheduler.truncate(null));
    }

    @Test void usesExponentialBackoffWithFiveMinuteCap() {
        assertEquals(1, DirectoryDeliveryScheduler.retryDelaySeconds(0));
        assertEquals(2, DirectoryDeliveryScheduler.retryDelaySeconds(1));
        assertEquals(4, DirectoryDeliveryScheduler.retryDelaySeconds(2));
        assertEquals(256, DirectoryDeliveryScheduler.retryDelaySeconds(8));
        assertEquals(300, DirectoryDeliveryScheduler.retryDelaySeconds(9));
        assertEquals(300, DirectoryDeliveryScheduler.retryDelaySeconds(100));
        assertEquals(14, DirectoryDeliveryScheduler.retryDelaySeconds(1, 7, 50));
        assertEquals(50, DirectoryDeliveryScheduler.retryDelaySeconds(5, 7, 50));
        assertEquals(Long.MAX_VALUE, DirectoryDeliveryScheduler.retryDelaySeconds(100, Long.MAX_VALUE / 2, Long.MAX_VALUE));
    }
    @Test void rejectsInvalidRetrySettings() {
        assertThrows(IllegalArgumentException.class, () -> new DirectoryDeliveryScheduler(null, null, null, "worker", "group", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new DirectoryDeliveryScheduler(null, null, null, "worker", "group", 10, 5));
    }
    @Test void publishesOnlyAfterTheCompleteFileIsCopied() throws Exception {
        Path directory = Files.createTempDirectory("m3-delivery-test-");
        Path source = directory.resolve("source.bin");
        Path target = directory.resolve("published.bin");
        Files.writeString(source, "complete payload");

        DirectoryDeliveryScheduler.copyAtomically(source, target);

        assertEquals("complete payload", Files.readString(target));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".part-")));
        }
    }
    @Test void lostLeasePreventsPublishingTemporaryCopy() throws Exception {
        Path directory = Files.createTempDirectory("m3-lost-lease-test-");
        Path source = directory.resolve("source");
        Path target = directory.resolve("target");
        Files.writeString(source, "payload");
        assertThrows(java.io.IOException.class, () -> DirectoryDeliveryScheduler.copyAtomically(source, target, () -> { throw new java.io.IOException("lease lost"); }));
        assertFalse(Files.exists(target));
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".part-"))); }
    }
    @Test void corruptedCopyIsNotPublished() throws Exception {
        Path directory = Files.createTempDirectory("m3-corrupt-delivery-");
        Path source = Files.writeString(directory.resolve("source"), "corrupted");
        Path target = directory.resolve("target");
        assertThrows(java.io.IOException.class, () -> DirectoryDeliveryScheduler.copyAtomically(source, target, "0".repeat(64), () -> {}));
        assertFalse(Files.exists(target));
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".part-"))); }
    }
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "M3_DISPOSABLE_POSTGRES", matches = "m3-fault-test-[a-z0-9]+")
    void postgresOutageAndLeaseExpiryAtDeliveryBoundaries(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String container = System.getenv("M3_DISPOSABLE_POSTGRES");
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(System.getenv("M3_TEST_POSTGRES_URL"), "m3test", "m3test");
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("community").defaultSchema("community").load().migrate();
        var control = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int scenario = 0; scenario < 5; scenario++) {
            Path output = dir.resolve("out-" + scenario);
            Path source = Files.writeString(dir.resolve("source-" + scenario), "test payload");
            var config = mapper.createObjectNode();
            config.putArray("recipients").addObject().put("id", "out").put("type", "directory").putObject("settings").put("path", output.toString());
            config.putArray("rules").addObject().put("id", "r").put("recipientId", "out");
            int version = control.queryForObject("select coalesce(max(version),0)+1 from community.config_bundle", Integer.class);
            control.update("insert into community.config_bundle(version,document_json) values (?,cast(? as jsonb))", version, config.toString());
            var message = java.util.UUID.randomUUID(); var job = java.util.UUID.randomUUID(); var group = java.util.UUID.randomUUID().toString();
            control.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?, ?,?,'file',?,'RECEIVED')", message, message.toString(), DeliveryRetryPostgresTest.checksum(source), source.toString());
            control.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,'r',?,?,'READY')", job, message, version, group);
            final int which = scenario;
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds) {
                int renewals;
                @Override public int update(String sql, Object... args) {
                    if (sql.startsWith("update community.execution_job set lease_until=now()")) {
                        renewals++;
                        if ((which == 0 && renewals == 1) || (which == 1 && renewals == 2)) docker(container, "stop");
                        if (which == 3 && renewals == 2) control.update("update community.execution_job set lease_until=now()-interval '1 second' where id=?", job);
                    }
                    if (which == 2 && sql.startsWith("update community.execution_job set status='DELIVERED'")) docker(container, "stop");
                    int result = super.update(sql, args);
                    if (which == 4 && sql.startsWith("update community.execution_job set lease_until=now()") && renewals == 1) docker(container, "stop");
                    return result;
                }
            };
            var scheduler = new DirectoryDeliveryScheduler(jdbc, mapper, new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds), "fault-test", group);
            try {
                if (scenario != 3) assertThrows(Exception.class, scheduler::dispatch);
                else scheduler.dispatch();
            } finally {
                if (scenario != 3) {
                    docker(container, "start");
                    for (int i = 0; ; i++) {
                        try { control.queryForObject("select 1", Integer.class); break; }
                        catch (Exception e) { if (i == 40) throw e; Thread.sleep(250); }
                    }
                }
            }
            if (scenario == 4) org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(output), "directory was created after database loss");
            Path target = output.resolve(job + "-file");
            assertEquals(scenario == 2, Files.exists(target), "published file in scenario " + scenario);
            if (Files.exists(output)) try (var paths = Files.list(output)) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".part-")));
            }
            assertEquals("RUNNING", control.queryForObject("select status from community.execution_job where id=?", String.class, job));
            control.update("update community.execution_job set lease_until=now()-interval '1 second' where id=?", job);
            new DirectoryDeliveryScheduler(control, mapper, new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds), "recovery", group).dispatch();
            assertEquals("UNCERTAIN", control.queryForObject("select status from community.execution_job where id=?", String.class, job));
            if (scenario == 2) assertEquals("test payload", Files.readString(target));
        }
    }

    private static void docker(String container, String operation) {
        try {
            Process process = new ProcessBuilder("docker", operation, container).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new IllegalStateException("docker " + operation + " failed: " + output);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
