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
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.when;

@WebMvcTest(ConfigurationController.class)
class ConfigurationControllerTest {
    @Test void warnsAboutExplicitUncertainAutoRetry() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"rules\":[{\"id\":\"r\",\"sourceIds\":[],\"autoRetryUncertain\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value("Rule 'r' allows automatic retry of uncertain deliveries when enabled on the worker; duplicate delivery is possible"));
    }
    @Test void rejectsAmbiguousRuleConditionsAndEnabledValues() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"rules\":[{\"id\":\"r\",\"sourceIds\":[],\"enabled\":\"false\",\"condition\":{\"fileNameEquals\":\"  \"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[1]").value("$.rules[0].enabled: must be a boolean"))
                .andExpect(jsonPath("$.errors[2]").value("$.rules[0].condition.fileNameEquals: nonempty filename required"));
    }
    @Test void rejectsInlinePasswordWithoutEchoingValue() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\",\"settings\":{\"path\":\"/in\",\"password\":\"do-not-echo\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0]").value("$.connections[0].settings.password: inline credentials are forbidden; use a secret reference"));
    }
    @Test void validatesPartialUpdateAgainstPublishedObjects() throws Exception {
        when(jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class)).thenReturn(1);
        when(jdbc.queryForObject("select document_json::text from community.config_bundle where version=?", String.class, 1))
                .thenReturn("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\",\"settings\":{\"path\":\"/in\"}}],\"sources\":[{\"id\":\"s\",\"connectionId\":\"c\"}],\"recipients\":[{\"id\":\"out\",\"type\":\"directory\",\"settings\":{\"path\":\"/out\"}}]}");
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"recipientId\":\"out\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors").isEmpty());
    }
    @Test void rejectsUnsupportedConditionsAndMissingDirectoryPath() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"c\",\"type\":\"directory\"}],\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"s\"],\"condition\":{\"regex\":\".*\"}}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors[0]").value("$.connections[0].settings.path: nonempty path required"))
                .andExpect(jsonPath("$.errors[1]").value("$.rules[0].condition: only fileNameEquals is supported"));
    }
    @Test void mergesUpdatesAndExplicitDeletes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var previous = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        var update = mapper.readTree("{\"formatVersion\":\"2\",\"connections\":[{\"id\":\"a\",\"type\":\"directory\"}],\"delete\":{\"connections\":[\"b\"]}}");
        var result = ConfigurationController.merge(previous, update);
        assertEquals(1, result.path("connections").size());
        assertEquals("directory", result.path("connections").get(0).path("type").asText());
        assertThrows(IllegalArgumentException.class, () -> ConfigurationController.merge(previous, mapper.readTree("{\"delete\":{\"sources\":[\"missing\"]}}")));
    }
    @Test void preservesObjectsOmittedFromUpdate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var result = ConfigurationController.merge(mapper.readTree("{\"sources\":[{\"id\":\"a\"}]}"), mapper.readTree("{\"formatVersion\":\"2\"}"));
        assertEquals("a", result.path("sources").get(0).path("id").asText());
    }
    @Autowired MockMvc mvc;
    @MockBean JdbcTemplate jdbc;
    @Test void rejectsMissingFormatVersion() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors[0]").value("$.formatVersion: required"));
    }
    @Test void rejectsRuleReferencingUnknownSource() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                .content("{\"formatVersion\":\"2\",\"sources\":[],\"recipients\":[],\"rules\":[{\"id\":\"r\",\"sourceIds\":[\"missing\"]}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors[0]").value("$.rules[0].sourceIds: unknown source 'missing'"));
    }
    @Test void rejectsSourceReferencingUnknownConnection() throws Exception {
        mvc.perform(post("/api/v2/configuration/validate").contentType("application/json")
                        .content("{\"formatVersion\":\"2\",\"connections\":[],\"sources\":[{\"id\":\"input\",\"connectionId\":\"missing\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0]").value("$.sources[0].connectionId: unknown connection 'missing'"));
    }
}
