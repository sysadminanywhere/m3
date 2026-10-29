package com.sysadminanywhere.m3.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectoryDeliverySchedulerTest {
    @Test void keepsErrorBoundedForPersistence() {
        assertEquals(1000, DirectoryDeliveryScheduler.truncate("x".repeat(1200)).length());
        assertEquals("unknown failure", DirectoryDeliveryScheduler.truncate(null));
    }
}
