package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.security.SecretService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecretController.class)
class SecretControllerTest {
    @Autowired MockMvc mvc;
    @MockBean SecretService secrets;

    @Test void rejectsEmptySecretWithoutCallingService() throws Exception {
        mvc.perform(put("/api/v2/secrets/community-token")
                        .contentType("application/json")
                        .content("{\"value\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("secret_id_and_value_required"));
    }
}
