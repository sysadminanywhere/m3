package com.sysadminanywhere.m3.messaging.outbound;

import java.util.Map;

/** Stored on the job before external I/O; contains no channel credentials. */
public record PreparedOutboundDelivery(long channelId, byte[] body, String payloadType, Map<String, String> metadata) { }
