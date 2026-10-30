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
    @Test void preservesOmittedObjectsAndRejectsReferencedDeletion(DriverManagerDataSource ds) throws Exception {
        var jdbc = new JdbcTemplate(ds); var mapper = new ObjectMapper();
        var controller = new ConfigurationController(jdbc, mapper);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var initial = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\",\"settings\":{\"path\":\"/in\"}}],\"sources\":[{\"id\":\"s\",\"connectionId\":\"c\"}],\"recipients\":[{\"id\":\"out\",\"type\":\"directory\",\"settings\":{\"path\":\"/out\"}}],\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"recipientId\":\"out\"}]}");
        var first = tx.execute(s -> controller.publish(new ConfigurationController.PublishRequest(initial, 0)));
        assertEquals(200, first.getStatusCode().value());
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
