package com.sysadminanywhere.m3.messaging.domain;

public enum MessageStatus {
    LOADED,
    PROCESSING,
    PROCESSING_FAILED,
    PENDING,
    SENT,
    FAILED,
    PROCESSED;

    public boolean isInboundProcessingStatus() {
        return this == LOADED || this == PROCESSING || this == PROCESSED || this == PROCESSING_FAILED;
    }
    public boolean isProcessingComplete() { return this == PROCESSED || this == PROCESSING_FAILED; }
}
