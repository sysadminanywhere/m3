package com.sysadminanywhere.m3.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationsController.class)
class OperationsControllerTest {
    @Autowired MockMvc mvc; @MockBean JdbcTemplate jdbc;
    @Test void exposesJobsRoute() throws Exception { mvc.perform(get("/api/v2/jobs")).andExpect(status().isOk()); }
}
