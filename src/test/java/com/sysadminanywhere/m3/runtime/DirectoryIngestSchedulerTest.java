package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryIngestSchedulerTest {
    @Test void recognizesSourceReference() throws Exception {
        Method method = DirectoryIngestScheduler.class.getDeclaredMethod("references", com.fasterxml.jackson.databind.JsonNode.class, String.class);
        method.setAccessible(true);
        var sources = new ObjectMapper().readTree("[\"one\",\"two\"]");
        assertTrue((boolean) method.invoke(null, sources, "two"));
        assertFalse((boolean) method.invoke(null, sources, "three"));
    }
}
