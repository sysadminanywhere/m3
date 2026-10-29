package com.sysadminanywhere.m3.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigurationController.class)
class ConfigurationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean JdbcTemplate jdbc;
    @Test void rejectsMissingFormatVersion() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors[0]").value("$.formatVersion: required"));
    }
    @Test void rejectsRuleReferencingUnknownSource() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"sources\":[],\"recipients\":[],\"rules\":[{\"sourceIds\":[\"missing\"]}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors[0]").value("$.rules[0].sourceIds: unknown source 'missing'"));
    }
}
