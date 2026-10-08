package com.sysadminanywhere.m3.messaging.domain;

/** Defines what an outbound rule writes to its destination transport. */
public enum OutboundPayloadMode {
    BODY,
    MESSAGE_ID
}
