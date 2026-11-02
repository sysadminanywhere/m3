package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.security.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecretController.class)
class SecretControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SecretStore secrets;

    @Test void rejectsEmptySecretWithoutCallingService() throws Exception {
        mvc.perform(put("/api/v2/secrets/community-token")
                        .contentType("application/json")
                        .content("{\"value\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("secret_id_and_value_required"));
    }
}
