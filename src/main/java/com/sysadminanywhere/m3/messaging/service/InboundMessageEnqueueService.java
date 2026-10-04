package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository;
import com.sysadminanywhere.m3.messaging.repository.RuleRepository;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class InboundMessageEnqueueService {
    private final MessageRepository messageRepository;
    private final RuleRepository ruleRepository;
    private final RuleExecutionJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final MessageReceiptOutbox receipts;

    public InboundMessageEnqueueService(MessageRepository messageRepository, RuleRepository ruleRepository,
                                        RuleExecutionJobRepository jobRepository, ObjectMapper objectMapper,
                                        MessageReceiptOutbox receipts) {
        this.messageRepository = messageRepository;
        this.ruleRepository = ruleRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
        this.receipts = receipts;
    }

    @Transactional
    public com.sysadminanywhere.m3.messaging.domain.Message enqueue(Object payload, MessageHeaders headers) {
        String source = stringHeader(headers, "sourceSystem", stringHeader(headers, "channelName", "unknown"));
        String target = stringHeader(headers, "targetSystem", null);
        String payloadType = stringHeader(headers, "payloadType", "json");
        String json = serialize(payload);
        var stored = new com.sysadminanywhere.m3.messaging.domain.Message(MessageDirection.INBOUND, json, payloadType);
        stored.setPayload(json);
        stored.setPayloadType(payloadType);
        stored.setSourceSystem(source);
        stored.setTargetSystem(target);
        headers.forEach((key, value) -> {
            if (!(payload instanceof byte[] && key.equals("encoding"))
                    && !java.util.Set.of("id", "timestamp", "metadata").contains(key)
                    && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                addMetadata(stored, key, value.toString());
            }
        });
        if (headers.get("metadata") instanceof Map<?, ?> metadata) {
            metadata.forEach((key, value) -> {
                if (!(key instanceof String) || !(value instanceof String))
                    throw new IllegalArgumentException("Metadata keys and values must be strings");
                if (headers.containsKey(key.toString()))
                    throw new IllegalArgumentException("Metadata cannot override a source header: " + key);
                if (payload instanceof byte[] && key.equals("encoding"))
                    throw new IllegalArgumentException("Binary payload encoding is managed by the ingestion service");
                addMetadata(stored, key.toString(), value.toString());
            });
        }
        if (payload instanceof byte[]) addMetadata(stored, "encoding", "base64");
        messageRepository.save(stored);
        receipts.record(stored.getId());

        var rules = ruleRepository.findBySourceChannelNameAndEnabled(source, true);
        int jobs = 0;
        for (Rule rule : rules) {
            if (!Boolean.TRUE.equals(rule.getEnabled()) || rule.getRuleType() != RuleType.INBOUND || rule.getWorkerPool() == null) continue;
            jobRepository.save(new RuleExecutionJob(stored, rule, rule.getWorkerPool()));
            jobs++;
        }
        if (jobs == 0) {
            stored.setStatus(MessageStatus.PROCESSED);
            stored.setProcessedAt(java.time.Instant.now());
        }
        return stored;
    }

    private static void addMetadata(com.sysadminanywhere.m3.messaging.domain.Message message, String key, String value) {
        if (key.isBlank() || key.length() > MessageMetadata.KEY_MAX_LENGTH || value.length() > MessageMetadata.VALUE_MAX_LENGTH)
            throw new IllegalArgumentException("Metadata exceeds the supported key/value lengths");
        message.addMetadata(key, value);
    }

    private String serialize(Object payload) {
        if (payload instanceof String value) return value;
        if (payload instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
        try { return objectMapper.writeValueAsString(payload); }
        catch (Exception e) { throw new IllegalArgumentException("Could not serialize inbound payload", e); }
    }

    private String stringHeader(MessageHeaders headers, String key, String defaultValue) {
        Object value = headers.get(key);
        return value == null ? defaultValue : value.toString();
    }
}
