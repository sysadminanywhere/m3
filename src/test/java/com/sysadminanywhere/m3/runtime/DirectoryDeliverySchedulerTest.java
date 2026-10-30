package com.sysadminanywhere.m3.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DirectoryDeliverySchedulerTest {
    @Test void keepsErrorBoundedForPersistence() {
        assertEquals(1000, DirectoryDeliveryScheduler.truncate("x".repeat(1200)).length());
        assertEquals("unknown failure", DirectoryDeliveryScheduler.truncate(null));
    }

    @Test void usesExponentialBackoffWithFiveMinuteCap() {
        assertEquals(1, DirectoryDeliveryScheduler.retryDelaySeconds(0));
        assertEquals(2, DirectoryDeliveryScheduler.retryDelaySeconds(1));
        assertEquals(4, DirectoryDeliveryScheduler.retryDelaySeconds(2));
        assertEquals(256, DirectoryDeliveryScheduler.retryDelaySeconds(8));
        assertEquals(300, DirectoryDeliveryScheduler.retryDelaySeconds(9));
        assertEquals(300, DirectoryDeliveryScheduler.retryDelaySeconds(100));
    }
    @Test void publishesOnlyAfterTheCompleteFileIsCopied() throws Exception {
        Path directory = Files.createTempDirectory("m3-delivery-test-");
        Path source = directory.resolve("source.bin");
        Path target = directory.resolve("published.bin");
        Files.writeString(source, "complete payload");

        DirectoryDeliveryScheduler.copyAtomically(source, target);

        assertEquals("complete payload", Files.readString(target));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".part-")));
        }
    }
}
