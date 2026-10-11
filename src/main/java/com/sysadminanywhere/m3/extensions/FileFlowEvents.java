package com.sysadminanywhere.m3.extensions;

import java.time.Instant;

/** Durable observations may be consumed by an optional distribution extension in the caller's transaction. */
public final class FileFlowEvents {
    private FileFlowEvents() { }
    public record Received(long ruleId, long messageId, String fileName, int size, Instant receivedAt) { }
    public record Delivered(long sourceMessageId, long messageId, long channelId, Instant deliveredAt) { }
}
