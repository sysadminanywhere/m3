package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL", matches=".+")
class ReprocessPostgresTest {
    @Test void readsExactOriginalAndReportsMissingContent(DriverManagerDataSource ds, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var jdbc=new JdbcTemplate(ds); var id=UUID.randomUUID(); var file=java.nio.file.Files.write(dir.resolve("original"),new byte[]{0,1,2,-1});
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s',?,'file',?,'RECEIVED')",id,id.toString(),file.toString());
        var controller=new OperationsController(jdbc); var response=controller.original(id);
        assertEquals(200,response.getStatusCode().value());
        assertArrayEquals(new byte[]{0,1,2,-1},response.getBody().getInputStream().readAllBytes());
        java.nio.file.Files.delete(file);
        assertEquals(503,controller.original(id).getStatusCode().value());
        assertEquals(404,controller.original(UUID.randomUUID()).getStatusCode().value());
    }
    @Test void reprocessesPreviouslyUnmatchedMessageUsingCurrentRulesAndGroup(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var id=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'source',?,'report.txt','key','NO_MATCH')",id,id.toString());
        jdbc.update("insert into community.config_bundle(version,document_json) values (2,cast(? as jsonb))", "{\"sources\":[{\"id\":\"source\",\"ruleSelection\":\"FIRST_MATCHING\"}],\"rules\":[{\"id\":\"one\",\"sourceIds\":[\"source\"],\"executionGroup\":\"new-group\",\"condition\":{\"fileNameEquals\":\"report.txt\"}},{\"id\":\"two\",\"sourceIds\":[\"source\"]}]}");
        assertEquals(202,tx.execute(s -> new OperationsController(jdbc).reprocess(id)).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("select count(*) from community.execution_job where message_id=?",Integer.class,id));
        assertEquals("new-group",jdbc.queryForObject("select execution_group from community.execution_job where message_id=?",String.class,id));
        assertEquals(2,jdbc.queryForObject("select rule_version from community.execution_job where message_id=?",Integer.class,id));
    }
}
