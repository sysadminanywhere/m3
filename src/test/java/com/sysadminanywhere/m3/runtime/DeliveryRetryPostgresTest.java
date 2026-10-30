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
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file',?,'RECEIVED')",message,message.toString(),source.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,'r',?,?,'READY')",job,message,version,group);
        var scheduler = new DirectoryDeliveryScheduler(jdbc,mapper,new DataSourceTransactionManager(ds),"test",group);
        scheduler.dispatch();
        assertEquals("RETRY",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        assertEquals(1,jdbc.queryForObject("select count(*) from community.job_event where job_id=? and status='RETRY'",Integer.class,job));
        Files.delete(blockingFile); Files.createDirectory(blockingFile);
        jdbc.update("update community.execution_job set next_attempt_at=now() where id=?",job);
        scheduler.dispatch();
        assertEquals("DELIVERED",jdbc.queryForObject("select status from community.execution_job where id=?",String.class,job));
        assertEquals("bytes",Files.readString(blockingFile.resolve("child").resolve(job + "-file")));
    }
}
