package com.sysadminanywhere.m3.api;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=PrometheusMetricsController.class,properties="M3_API_TOKEN=test-token")
class PrometheusMetricsAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @Test void scrapeRequiresTechnicalTokenAndUsesPrometheusContentType() throws Exception {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("ready",0,"retry",0,"running",0,"uncertain",0,"delivered",0,"delivered_hour",0,"oldest_seconds",0));
        mvc.perform(get("/api/v2/metrics/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/metrics/prometheus").header("X-M3-Service-Token","test-token"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type",org.hamcrest.Matchers.containsString("text/plain")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("m3_jobs{status=\"READY\"} 0")));
    }
    @Test void databaseFailureDoesNotProduceZeroMetrics() throws Exception {
        reset(jdbc);
        when(jdbc.queryForMap(anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThrowsServletFailure();
    }
    private void assertThrowsServletFailure() {
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.servlet.ServletException.class,() ->
                mvc.perform(get("/api/v2/metrics/prometheus").header("X-M3-Service-Token","test-token")));
    }
}
