package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NotificationRecipientSettingsTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void validatesDestinationsWithoutAcceptingInlineSecrets() throws Exception {
        assertDoesNotThrow(() -> NotificationRecipientSettings.validate("webhook",mapper.readTree("{\"url\":\"https://example.org/hook\",\"tokenRef\":\"token\"}")));
        assertDoesNotThrow(() -> NotificationRecipientSettings.validate("webhook",mapper.readTree("{\"url\":\"http://127.0.0.1:1234/hook\"}")));
        for (String url : new String[]{"http://example.org/hook","https://user:pass@example.org/","https://example.org/?secret=x"}) {
            var node=mapper.createObjectNode().put("url",url);
            assertThrows(IllegalArgumentException.class,() -> NotificationRecipientSettings.validate("webhook",node));
        }
        assertThrows(IllegalArgumentException.class,() -> NotificationRecipientSettings.validate("webhook",mapper.readTree("{\"url\":\"https://example.org\",\"token\":\"secret\"}")));
        var email=mapper.readTree("{\"host\":\"smtp.example.org\",\"port\":587,\"from\":\"a@example.org\",\"to\":\"b@example.org\",\"username\":\"a\",\"passwordRef\":\"smtp-password\"}");
        assertDoesNotThrow(() -> NotificationRecipientSettings.validate("email",email));
        ((com.fasterxml.jackson.databind.node.ObjectNode)email).put("allowPlaintextLoopback",true);
        assertThrows(IllegalArgumentException.class,() -> NotificationRecipientSettings.validate("email",email));
    }
}
