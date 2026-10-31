package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL", matches=".+")
class ConfigurationPostgresTest {
    @Test void groupMoveWarnsBeforeAndDuringPublicationAndPreservesExistingJobs(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        var controller = new ConfigurationController(jdbc, mapper); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var initial = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\",\"settings\":{\"path\":\"/in\"}}],\"sources\":[{\"id\":\"s\",\"connectionId\":\"c\"}],\"recipients\":[{\"id\":\"out\",\"type\":\"directory\",\"settings\":{\"path\":\"/out\"}}],\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"recipientId\":\"out\"}]}");
        assertEquals(200, tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(initial, 0))).getStatusCode().value());
        var message=java.util.UUID.randomUUID(); var job=java.util.UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'s','checksum','file','key','RECEIVED')", message);
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status) values (?,?,'r',1,'default','UNCERTAIN')", job,message);
        var patch=mapper.readTree("{\"formatVersion\":\"2\",\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"recipientId\":\"out\",\"executionGroup\":\"new\"}]}");
        assertTrue(controller.validate(patch).warnings().stream().anyMatch(w -> w.contains("moves from group 'default' to 'new'")));
        var response=tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(patch, 1)));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(((java.util.List<?>) ((java.util.Map<?,?>) response.getBody()).get("warnings")).stream().anyMatch(w -> w.toString().contains("existing jobs remain")));
        assertEquals("default", jdbc.queryForObject("select execution_group from community.execution_job where id=?", String.class, job));
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from community.execution_job where id=?", String.class, job));
    }
    @Test void rejectsAmbiguousRuleWithoutPublishingPartialConfiguration(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        var controller = new ConfigurationController(jdbc, mapper);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var document = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\",\"settings\":{\"path\":\"/in\"}}],\"sources\":[{\"id\":\"s\",\"connectionId\":\"c\"}],\"recipients\":[{\"id\":\"out\",\"type\":\"directory\",\"settings\":{\"path\":\"/out\"}}],\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"recipientId\":\"out\",\"condition\":{\"fileNameEquals\":\" \"}}]}");
        assertEquals(400, tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(document, 0))).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject("select count(*) from community.config_bundle", Integer.class));
    }
    @Test void preservesOmittedObjectsAndRejectsReferencedDeletion(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        var controller = new ConfigurationController(jdbc, mapper);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var initial = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\",\"settings\":{\"path\":\"/in\"}}],\"sources\":[{\"id\":\"s\",\"connectionId\":\"c\"}],\"recipients\":[{\"id\":\"out\",\"type\":\"directory\",\"settings\":{\"path\":\"/out\"}}],\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"recipientId\":\"out\"}]}");
        var first = tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(initial, 0)));
        assertEquals(200, first.getStatusCode().value());
        assertEquals(1, controller.current().get("version"));
        assertEquals("c", ((com.fasterxml.jackson.databind.JsonNode) controller.current().get("document")).path("connections").get(0).path("id").asText());
        var patch = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"other\",\"type\":\"directory\",\"settings\":{\"path\":\"/other\"}}]}");
        assertEquals(200, tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(patch, 1))).getStatusCode().value());
        var stored = mapper.readTree(jdbc.queryForObject("select document_json::text from community.config_bundle where version=2", String.class));
        assertEquals(2, stored.path("connections").size()); assertEquals(1, stored.path("rules").size());
        var remove = mapper.readTree("{\"formatVersion\":\"2\",\"delete\":{\"connections\":[\"c\"]}}");
        assertEquals(400, tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(remove, 2))).getStatusCode().value());
        assertEquals(2, jdbc.queryForObject("select max(version) from community.config_bundle", Integer.class));
        assertEquals(409, tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(patch, 1))).getStatusCode().value());
    }
}
