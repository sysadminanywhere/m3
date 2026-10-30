package com.sysadminanywhere.m3.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.io.TempDir;
import com.sysadminanywhere.m3.runtime.OrphanCleanupScheduler;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL", matches=".+")
class RetentionPostgresTest {
    @Test void protectsActiveAndRetainedMessagesAndAuditsDeletion(@TempDir Path storage, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var controller = new OperationsController(jdbc); var message = UUID.randomUUID(); var job = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file','key','RECEIVED')", message, UUID.randomUUID().toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')", job, message);
        assertEquals(409, tx.execute(s -> controller.deleteMessage(message)).getStatusCode().value());
        jdbc.update("update community.execution_job set status='DELIVERED' where id=?", job);
        tx.execute(s -> controller.retention(message, new OperationsController.RetentionRequest(OffsetDateTime.now().plusDays(1))));
        assertEquals(409, tx.execute(s -> controller.deleteMessage(message)).getStatusCode().value());
        tx.execute(s -> controller.retention(message, new OperationsController.RetentionRequest(null)));
        assertEquals(202, tx.execute(s -> controller.deleteMessage(message)).getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("select count(*) from community.message_deletion_audit where message_id=?", Integer.class, message));
        assertEquals(2, jdbc.queryForObject("select jsonb_array_length(job_history->0->'events') from community.message_deletion_audit where message_id=?", Integer.class, message));
        assertEquals("file", jdbc.queryForObject("select message_snapshot->>'original_name' from community.message_deletion_audit where message_id=?", String.class, message));
        assertEquals(0, jdbc.queryForObject("select count(*) from community.message_record where id=?", Integer.class, message));
        String checksum = "aa" + "0".repeat(62);
        Path directory = Files.createDirectory(storage.resolve("aa"));
        Path file = Files.writeString(directory.resolve(checksum), "orphan");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(30L * 86400)));
        var cleanup = new OrphanCleanupScheduler(jdbc, storage.toString(), 168);
        tx.executeWithoutResult(s -> cleanup.cleanup());
        assertTrue(Files.exists(file), "Old content must get a full orphan grace period");
        jdbc.update("update community.orphan_candidate set first_seen=now() - interval '8 days' where checksum=?", checksum);
        tx.executeWithoutResult(s -> cleanup.cleanup());
        assertFalse(Files.exists(file));
    }

    @Test void protectsStorageKeyAndRestartsGraceAfterReferenceDisappears(@TempDir Path storage, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var controller = new OperationsController(jdbc);
        String checksum = "bb" + "1".repeat(62);
        Path file = Files.writeString(Files.createDirectory(storage.resolve("bb")).resolve(checksum), "content");
        var cleanup = new OrphanCleanupScheduler(jdbc, storage.toString(), 168);
        tx.executeWithoutResult(s -> cleanup.cleanup());
        jdbc.update("update community.orphan_candidate set first_seen=now()-interval '8 days' where checksum=?", checksum);
        UUID message = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'alias',?,'name',?,'NO_MATCH')", message, UUID.randomUUID().toString(), file.toString());
        tx.executeWithoutResult(s -> cleanup.cleanup());
        assertTrue(Files.exists(file));
        assertEquals(0, jdbc.queryForObject("select count(*) from community.orphan_candidate where checksum=?", Integer.class, checksum));
        jdbc.update("insert into community.orphan_candidate(checksum,first_seen) values (?,now()-interval '8 days')", checksum);
        assertEquals(202, tx.execute(s -> controller.deleteMessage(message)).getStatusCode().value());
        tx.executeWithoutResult(s -> cleanup.cleanup());
        assertTrue(Files.exists(file), "A reference removed between scans must start a new grace period");
    }

    @Test void retryAndDeletionSerializeWithoutLosingHistory(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var controller = new OperationsController(jdbc); UUID message = UUID.randomUUID(), job = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'race',?,'file','key','RECEIVED')", message, UUID.randomUUID().toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status,completed_at) values (?,?,'r',1,'DELIVERED',now())", job, message);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var retry = pool.submit(() -> tx.execute(s -> {
                var result = controller.retry(job); locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return result;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var deletion = pool.submit(() -> tx.execute(s -> controller.deleteMessage(message)));
            try { assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertEquals(202, retry.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(409, deletion.get(5, TimeUnit.SECONDS).getStatusCode().value());
        }
        assertNull(jdbc.queryForObject("select completed_at from community.execution_job where id=?", java.sql.Timestamp.class, job));
        assertEquals(2, jdbc.queryForObject("select count(*) from community.job_event where job_id=?", Integer.class, job));
        assertEquals(409, controller.retry(job).getStatusCode().value());
        assertEquals(2, jdbc.queryForObject("select count(*) from community.job_event where job_id=?", Integer.class, job));
        jdbc.update("update community.execution_job set status='UNCERTAIN' where id=?", job);
        assertEquals(202, controller.retry(job).getStatusCode().value());
        assertEquals(4, jdbc.queryForObject("select count(*) from community.job_event where job_id=?", Integer.class, job));
    }

    @Test void reprocessAndDeletionSerialize(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var controller = new OperationsController(jdbc); UUID message = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'reprocess',?,'file','key','RECEIVED')", message, UUID.randomUUID().toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'DELIVERED')", UUID.randomUUID(), message);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reprocess = pool.submit(() -> tx.execute(s -> {
                var result = controller.reprocess(message); locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return result;
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var deletion = pool.submit(() -> tx.execute(s -> controller.deleteMessage(message)));
            try { assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertEquals(202, reprocess.get(5, TimeUnit.SECONDS).getStatusCode().value());
            assertEquals(409, deletion.get(5, TimeUnit.SECONDS).getStatusCode().value());
        }
    }

    @Test void cleanupWaitsForIngestStorageTransaction(@TempDir Path storage, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String checksum = "cc" + "2".repeat(62);
        Path file = Files.writeString(Files.createDirectory(storage.resolve("cc")).resolve(checksum), "content");
        jdbc.update("insert into community.orphan_candidate(checksum,first_seen) values (?,now()-interval '8 days')", checksum);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ingest = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.execute("select pg_advisory_xact_lock(8273643)");
                jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'ingest',?,'file',?,'NO_MATCH')", UUID.randomUUID(), checksum, file.toString());
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var cleanup = pool.submit(() -> tx.executeWithoutResult(s -> new OrphanCleanupScheduler(jdbc, storage.toString(), 168).cleanup()));
            try { assertThrows(TimeoutException.class, () -> cleanup.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            ingest.get(5, TimeUnit.SECONDS); cleanup.get(5, TimeUnit.SECONDS);
        }
        assertTrue(Files.exists(file));
        assertEquals(0, jdbc.queryForObject("select count(*) from community.orphan_candidate where checksum=?", Integer.class, checksum));
    }
}
