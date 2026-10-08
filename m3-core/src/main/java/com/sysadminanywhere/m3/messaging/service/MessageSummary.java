package com.sysadminanywhere.m3.messaging.service;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import java.time.Instant;
/** List rows deliberately contain no payload bytes or metadata collections. */
public record MessageSummary(Long id,MessageStatus status,String sourceSystem,String targetSystem,String payloadType,Instant createdAt,boolean archived) {
    public Long getId() { return id; }
    public MessageStatus getStatus() { return status; }
    public String getSourceSystem() { return sourceSystem; }
    public String getTargetSystem() { return targetSystem; }
    public String getPayloadType() { return payloadType; }
    public Instant getCreatedAt() { return createdAt; }
}
