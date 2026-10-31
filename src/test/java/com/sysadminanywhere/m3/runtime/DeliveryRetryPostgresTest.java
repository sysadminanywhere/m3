package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL", matches=".+")
class DeliveryRetryPostgresTest {
    @Test void uncertainAutoRetryRequiresBothOptInsAndKeepsHistory(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        Path original = Files.writeString(dir.resolve("original"), "payload"); Path output = dir.resolve("out");
        var config = mapper.createObjectNode();
        config.putArray("recipients").addObject().put("id", "out").put("type", "directory").putObject("settings").put("path", output.toString());
        var rules = config.putArray("rules");
        rules.addObject().put("id", "auto").put("recipientId", "out").put("autoRetryUncertain", true);
        rules.addObject().put("id", "manual").put("recipientId", "out").put("autoRetryUncertain", false);
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", config.toString());
        UUID message = UUID.randomUUID(), auto = UUID.randomUUID(), manual = UUID.randomUUID(), otherGroup = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file',?,'RECEIVED')", message, checksum(original), original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status,attempts,claimed_by,lease_until) values (?,?,'auto',1,'on','RUNNING',1,'crashed',now()-interval '1 minute')", auto, message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status,attempts) values (?,?,'manual',1,'on','UNCERTAIN',1),(?,?,'auto',1,'other','UNCERTAIN',1)", manual, message, otherGroup, message);
        var tx = new DataSourceTransactionManager(ds);
        new DirectoryDeliveryScheduler(jdbc, mapper, tx, "off", "on", 1, 300, false).dispatch();
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, auto));
        var enabled = new DirectoryDeliveryScheduler(jdbc, mapper, tx, "on", "on", 1, 300, true);
        enabled.dispatch();
        assertEquals("RETRY", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, auto));
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, manual));
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, otherGroup));
        jdbc.update("update community.execution_job set next_attempt_at=now() where id=?", auto);
        enabled.dispatch();
        assertEquals("DELIVERED", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, auto));
        assertEquals("payload", Files.readString(output.resolve(auto + "-file")));
        assertEquals(java.util.List.of("RUNNING", "UNCERTAIN", "RETRY", "RUNNING", "DELIVERED"), jdbc.queryForList("select status from community.job_event where job_id=? order by id", String.class, auto));
    }
    @Test void damagedOriginalIsNotDelivered(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        Path source = Files.writeString(dir.resolve("original"), "original");
        String expected = checksum(source); Files.writeString(source, "damaged");
        Path output = dir.resolve("out");
        var config = mapper.createObjectNode();
        config.putArray("recipients").addObject().put("id", "out").put("type", "directory").putObject("settings").put("path", output.toString());
        config.putArray("rules").addObject().put("id", "r").put("recipientId", "out");
        jdbc.update("insert into community.config_bundle(version,document_json) values (1,cast(? as jsonb))", config.toString());
        UUID message = UUID.randomUUID(), job = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file',?,'RECEIVED')", message, expected, source.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,status) values (?,?,'r',1,'READY')", job, message);
        new DirectoryDeliveryScheduler(jdbc, mapper, new DataSourceTransactionManager(ds), "worker", "default").dispatch();
        assertEquals("RETRY", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, job));
        assertEquals("Original content checksum mismatch", jdbc.queryForObject("select last_error from community.execution_job where id=?", String.class, job));
        assertFalse(Files.exists(output.resolve(job + "-file")));
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
    }
    @Test void movedRuleContinuesInNewGroupWhileExpiredOldJobRemainsUncertain(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        Path original = Files.write(dir.resolve("original"), new byte[]{0, 1, 2, (byte) 255});
        Path oldOutput = dir.resolve("old"), newOutput = dir.resolve("new");
        for (int version = 1; version <= 2; version++) {
            var config = mapper.createObjectNode();
            config.putArray("recipients").addObject().put("id", "out").put("type", "directory")
                    .putObject("settings").put("path", (version == 1 ? oldOutput : newOutput).toString());
            config.putArray("rules").addObject().put("id", "r").put("recipientId", "out")
                    .put("processingMode", "SEQUENTIAL").put("onError", "STOP");
            jdbc.update("insert into community.config_bundle(version,document_json) values (?,cast(? as jsonb))", version, config.toString());
        }
        UUID message = UUID.randomUUID(), oldJob = UUID.randomUUID(), newJob = UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file',?,'RECEIVED')", message, checksum(original), original.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status,claimed_by,lease_until) values (?,?,'r',1,'old','RUNNING','crashed',now()-interval '1 minute')", oldJob, message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,'r',2,'new','READY')", newJob, message);
        new DirectoryDeliveryScheduler(jdbc, mapper, new DataSourceTransactionManager(ds), "new-worker", "new").dispatch();
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, oldJob));
        assertEquals("DELIVERED", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, newJob));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(newOutput.resolve(newJob + "-file")));
        assertFalse(Files.exists(oldOutput));
        assertEquals(1, jdbc.queryForObject("select count(*) from community.job_event where job_id=? and status='UNCERTAIN'", Integer.class, oldJob));
        assertEquals(202, new com.sysadminanywhere.m3.api.OperationsController(jdbc).retry(oldJob).getStatusCode().value());
        new DirectoryDeliveryScheduler(jdbc, mapper, new DataSourceTransactionManager(ds), "old-worker", "old").dispatch();
        assertEquals("DELIVERED", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, oldJob));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(oldOutput.resolve(oldJob + "-file")), "Retry must use the original configuration version");
    }
    @Test void failureBeforePublicationIsRetriedAndRecorded(@TempDir Path dir, DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        Path blockingFile = Files.writeString(dir.resolve("not-a-directory"), "x");
        Path source = Files.writeString(dir.resolve("original"), "bytes");
        var config = mapper.createObjectNode(); var recipient = config.putArray("recipients").addObject();
        recipient.put("id", "out").put("type", "directory").putObject("settings").put("path", blockingFile.resolve("child").toString());
        config.putArray("rules").addObject().put("id", "r").put("recipientId", "out");
        int version = jdbc.queryForObject("select coalesce(max(version),0)+1 from community.config_bundle", Integer.class);
        jdbc.update("insert into community.config_bundle(version,document_json) values (?,cast(? as jsonb))",version,config.toString());
        UUID message=UUID.randomUUID(), job=UUID.randomUUID(); String group=UUID.randomUUID().toString();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file',?,'RECEIVED')",message,checksum(source),source.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,'r',?,?,'READY')",job,message,version,group);
        var scheduler = new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test",group,11,30);
        scheduler.dispatch();
        assertEquals("RETRY",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select next_attempt_at between now()+interval '15 seconds' and now()+interval '23 seconds' from community.execution_job where id=?", Boolean.class, job));
        assertEquals(1,jdbc.queryForObject("select count(*) from community.job_event where job_id=? and status='RETRY'",Integer.class,job));
        Files.delete(blockingFile); Files.createDirectory(blockingFile);
        jdbc.update("update community.execution_job set next_attempt_at=now() where id=?",job);
        scheduler.dispatch();
        assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        assertEquals("bytes",Files.readString(blockingFile.resolve("child").resolve(job + "-file")));
    }
    static String checksum(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
