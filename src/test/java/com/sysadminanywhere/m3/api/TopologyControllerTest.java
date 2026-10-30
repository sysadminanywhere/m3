package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopologyControllerTest {
    @Test void includesConfiguredSourcesAndGroupsWithoutMessages() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("{\"sources\":[{\"id\":\"empty\"}],\"rules\":[{\"id\":\"r\",\"executionGroup\":\"east\"},{\"id\":\"r2\"}]}"));
        var result=new TopologyController(jdbc,new ObjectMapper()).topology();
        assertEquals(List.of("empty"),result.get("sources"));
        assertEquals(List.of("east","default"),result.get("groups"));
    }
}
