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

    @Test void stableObservationRequiresAnUnchangedFileWindow() throws Exception {
        var first = new DirectoryIngestScheduler.FileObservation(10, 100, 1_000);
        var unchanged = new DirectoryIngestScheduler.FileObservation(10, 100, 2_100);
        var changed = new DirectoryIngestScheduler.FileObservation(11, 100, 2_100);

        assertFalse(DirectoryIngestScheduler.isStableObservation(null, first, 2_000, 1_000));
        assertTrue(DirectoryIngestScheduler.isStableObservation(first, unchanged, 2_100, 1_000));
        assertFalse(DirectoryIngestScheduler.isStableObservation(first, changed, 2_100, 1_000));
    }
}
