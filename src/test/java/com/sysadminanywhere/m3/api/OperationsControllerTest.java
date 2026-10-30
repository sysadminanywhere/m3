package com.sysadminanywhere.m3.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(OperationsController.class)
class OperationsControllerTest {
    @Autowired MockMvc mvc; @MockBean JdbcTemplate jdbc;
    @Test void exposesJobsRoute() throws Exception { mvc.perform(get("/api/v2/jobs")).andExpect(status().isOk()); }

    @Test void exposesExecutionGroupForOperationalRouting() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(new OperationsController.JobSummary(
                UUID.randomUUID(), UUID.randomUUID(), "rule", 1, "alpha", "READY", 0, null,
                OffsetDateTime.now(), null)));

        mvc.perform(get("/api/v2/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].executionGroup").value("alpha"));
    }
}
