package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.test.DisposablePostgres;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL",matches=".+")
class NotificationRecipientStorePostgresTest {
    @Test void preservesRevisionsAndRejectsConcurrentUpdates(DriverManagerDataSource ds) throws Exception {
        var store=new NotificationRecipientStore(new JdbcTemplate(ds)); var mapper=new ObjectMapper();
        var initial=new NotificationRecipientStore.Settings("Operations","webhook",true,List.of("RETRY","UNCERTAIN"),0,10,
                mapper.readTree("{\"url\":\"https://example.org/events\",\"tokenRef\":\"token\"}"));
        var id=store.create(initial);
        var updated=new NotificationRecipientStore.Settings("Operations updated","webhook",false,List.of("RETRY"),0,30,initial.destination());
        assertTrue(store.update(id,1,updated)); assertFalse(store.update(id,1,initial));
        var jdbc=new JdbcTemplate(ds);
        assertEquals(2L,jdbc.queryForObject("select revision from community.notification_recipient where id=?",Long.class,id));
        assertEquals("Operations updated",jdbc.queryForObject("select name from community.notification_recipient where id=?",String.class,id));
        assertEquals(List.of("RETRY"),store.list().get(0).settings().events());
        assertFalse(jdbc.queryForObject("select settings::text from community.notification_recipient where id=?",String.class,id).contains("password"));
        var duplicateEvents=new NotificationRecipientStore.Settings("Bad","webhook",true,List.of("RETRY","RETRY"),0,10,initial.destination());
        assertThrows(IllegalArgumentException.class,() -> store.create(duplicateEvents));
        var controller=new com.sysadminanywhere.m3.api.NotificationRecipientController(store);
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.update(id,new com.sysadminanywhere.m3.api.NotificationRecipientController.UpdateRequest(1,initial))).getStatusCode());
        assertFalse(store.delete(id,1)); assertTrue(store.delete(id,2)); assertTrue(store.list().isEmpty());
    }
}
