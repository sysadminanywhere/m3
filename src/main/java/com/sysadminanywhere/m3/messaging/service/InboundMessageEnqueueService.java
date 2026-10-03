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

    public InboundMessageEnqueueService(MessageRepository messageRepository, RuleRepository ruleRepository,
                                        RuleExecutionJobRepository jobRepository, ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.ruleRepository = ruleRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(Object payload, MessageHeaders headers) {
        String source = stringHeader(headers, "sourceSystem", stringHeader(headers, "channelName", "unknown"));
        String target = stringHeader(headers, "targetSystem", null);
        String payloadType = stringHeader(headers, "payloadType", "json");
        String json = serialize(payload);
        var stored = new com.sysadminanywhere.m3.messaging.domain.Message(MessageDirection.INBOUND, json, payloadType);
        stored.setSourceSystem(source);
        stored.setTargetSystem(target);
        for (String key : new String[]{"fileName", "fileSize", "encoding", "filePath", "channelName"}) {
            Object value = headers.get(key);
            if (value != null) stored.addMetadata(key, value.toString());
        }
        stored = messageRepository.save(stored);

        var rules = ruleRepository.findBySourceChannelNameAndEnabled(source, true);
        int jobs = 0;
        for (Rule rule : rules) {
            if (!Boolean.TRUE.equals(rule.getEnabled()) || rule.getWorkerPool() == null) continue;
            jobRepository.save(new RuleExecutionJob(stored, rule, rule.getWorkerPool()));
            jobs++;
        }
        if (jobs == 0) {
            stored.setStatus(MessageStatus.PROCESSED);
            stored.setProcessedAt(java.time.Instant.now());
        }
    }

    private String serialize(Object payload) {
        if (payload instanceof String value) return value;
        if (payload instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        try { return objectMapper.writeValueAsString(payload); }
        catch (Exception e) { throw new IllegalArgumentException("Could not serialize inbound payload", e); }
    }

    private String stringHeader(MessageHeaders headers, String key, String defaultValue) {
        Object value = headers.get(key);
        return value == null ? defaultValue : value.toString();
    }
}
