package com.sysadminanywhere.m3.runtime;

import com.sysadminanywhere.m3.security.RuntimeSecretResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers=SftpConnectionCheckController.class,properties="M3_API_TOKEN=test-token")
class SftpConnectionCheckAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean RuntimeSecretResolver secrets;
    @Test void requiresTokenAndRejectsUnsafePathBeforeResolvingSecret() throws Exception {
        String request="{\"host\":\"localhost\",\"credentialRef\":\"ref\",\"path\":\"/../escape\"}";
        mvc.perform(post("/api/v2/sftp/check").contentType("application/json").content(request)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/sftp/check").header("X-M3-Service-Token","test-token").contentType("application/json").content(request)).andExpect(status().isBadRequest());
        verifyNoInteractions(secrets);
    }
}
