package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class PayloadMaskingTest {
    private final ObjectMapper json=new ObjectMapper();
    private final PayloadMasking masking=new PayloadMasking(json,"phone,cardNumber,password,authorization","$.customers[*].privateCode","//account/code");
    @Test void masksNestedFieldsPathsAndNumericPatternsWithoutChangingSource() throws Exception {
        String source="{\"customers\":[{\"privateCode\":\"secret-a\",\"Phone\":\"123\",\"name\":\"Alice\"}],\"cardNumber\":4111111111111111,\"note\":\"call +7 (999) 123-45-67\"}";
        String result=masking.payload(source,"application/json");
        assertThat(result).doesNotContain("secret-a","4111111111111111","123-45-67").contains("Alice","[REDACTED]");
        assertThat(json.readTree(result).path("customers").get(0).path("Phone").asText()).isEqualTo("[REDACTED]");
        assertThat(source).contains("secret-a","4111111111111111");
    }
    @Test void masksXmlElementsAttributesAndXPath() {
        assertThat(masking.payload("<account password=\"secret-p\"><code>secret-c</code><name>Alice</name><phone>123</phone></account>","application/xml"))
                .doesNotContain("secret-p","secret-c",">123<").contains("Alice","[REDACTED]");
    }
    @Test void failsClosedForBrokenStructuredPayloadUnknownBytesAndExternalEntities() {
        assertThat(masking.payload("{\"password\":\"secret\"","application/json")).isEqualTo("[REDACTED]");
        assertThat(masking.payload("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>","application/xml")).isEqualTo("[REDACTED]");
        assertThat(masking.payload(new byte[]{-1},"UTF-8","application/json")).isEqualTo("[REDACTED]");
        assertThat(masking.payload("secret".getBytes(StandardCharsets.UTF_8),null,"application/octet-stream")).isEqualTo("[REDACTED]");
    }
    @Test void metadataAndDiagnosticsCannotLeakSecrets() {
        assertThat(masking.metadata("Authorization","Bearer secret")).isEqualTo("[REDACTED]");
        assertThat(masking.diagnostic("receiver returned password=secret")).isEqualTo("[REDACTED]");
        assertThat(masking.metadata("traceId","trace-42")).isEqualTo("trace-42");
    }
    @Test void policyVersionChangesAndInvalidPathsAreRejected() {
        assertThat(new PayloadMasking(json,"phone","","").version()).isNotEqualTo(masking.version());
        assertThatThrownBy(() -> new PayloadMasking(json,"phone","$.a[?(@.x)]","")).isInstanceOf(IllegalArgumentException.class);
    }
}
