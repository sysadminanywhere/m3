package com.sysadminanywhere.m3.messaging.broker;

import java.time.Instant;
import java.util.UUID;

public record MessageReceivedEvent(UUID eventId, String eventType, int schemaVersion,
                                   long messageId, Instant receivedAt, String resourcePath) { }
