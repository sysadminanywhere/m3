package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RuleExecutionProcessor {
    private final RuleExecutionJobRepository jobs;
    private final MessageRepository messages;
    private final com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository channels;
    private final RuleEngine ruleEngine;
    private final ObjectMapper objectMapper;
    private final String poolName;
    private final String workerId;
    private final int claimLeaseSeconds;

    @PersistenceContext
    private EntityManager entityManager;

    public RuleExecutionProcessor(RuleExecutionJobRepository jobs, MessageRepository messages,
                                  RuleEngine ruleEngine, ObjectMapper objectMapper,
                                  com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository channels,
                                  @Value("${m3.worker.pool:default}") String poolName,
                                  @Value("${HOSTNAME:local-worker}") String workerId,
                                  @Value("${m3.worker.claim-lease-seconds:300}") int claimLeaseSeconds) {
        this.jobs = jobs;
        this.messages = messages;
        this.channels = channels;
        this.ruleEngine = ruleEngine;
        this.objectMapper = objectMapper;
        this.poolName = poolName;
        this.workerId = workerId;
        this.claimLeaseSeconds = claimLeaseSeconds;
    }

    @Transactional
    public boolean processNext() {
        @SuppressWarnings("unchecked")
        List<Number> rows = entityManager.createNativeQuery("""
                select j.job_id from rule_execution_job j
                join rule r on r.rule_id=j.rule_id
                join rule_worker_pool p on p.worker_pool_id = r.worker_pool_id
                join message m on m.message_id = j.message_id
                where p.name = :pool and j.status = 'PENDING' and m.direction = 'INBOUND'
                order by j.created_at, j.job_id
                limit 1 for update of j skip locked
                """)
                .setParameter("pool", poolName)
                .getResultList();
        if (rows.isEmpty()) return false;

        long id = rows.getFirst().longValue();
        var job = jobs.findById(id).orElseThrow();
        job.reassignPool(job.getRule().getWorkerPool());
        job.claim(workerId);
        var message = job.getMessage();
        try {
            execute(job, message);
            job.complete();
        } catch (Exception error) {
            job.fail(error);
        }
        entityManager.flush();
        entityManager.lock(message, LockModeType.PESSIMISTIC_WRITE);
        long pending = jobs.countByMessage_IdAndStatusIn(message.getId(),
                List.of(RuleJobStatus.PENDING, RuleJobStatus.PROCESSING));
        if (pending == 0) {
            long failed = jobs.countByMessage_IdAndStatus(message.getId(), RuleJobStatus.FAILED);
            if (failed == 0) {
                try { finishMessage(message); }
                catch (Exception e) {
                    job.fail(e);
                    setFinalStatus(message, MessageStatus.FAILED);
                }
            } else setFinalStatus(message, MessageStatus.FAILED);
        }
        return true;
    }

    @Transactional
    public int reclaimExpired() {
        return jobs.releaseExpiredClaims(poolName, claimLeaseSeconds);
    }

    private void execute(RuleExecutionJob job, Message stored) throws Exception {
        Object payload = RulePayloads.readForRules(stored, objectMapper, List.of(job.getRule()));
        Map<String, Object> headers = new HashMap<>();
        headers.put("channelName", stored.getSourceSystem());
        headers.put("sourceSystem", stored.getSourceSystem());
        stored.getMetadata().forEach(metadata -> headers.put(metadata.getKey(), metadata.getValue()));
        var input = org.springframework.messaging.support.MessageBuilder.withPayload(payload).copyHeaders(headers).build();
        Rule rule = job.getRule();
        if (rule.getRuleType()!=RuleType.INBOUND)
            throw new IllegalArgumentException("Loading rule changed type before the message was processed");
        if (!Boolean.TRUE.equals(rule.getEnabled()) || !ruleEngine.evaluateConditions(rule, input)) {
            job.setResult("NO_MATCH");
            return;
        }
        job.setResult("MATCHED");
    }

    private void finishMessage(Message stored) throws Exception {
        var completedJobs = jobs.findByMessage_IdOrderByRule_PriorityAsc(stored.getId());
        List<Rule> matchedRules = completedJobs.stream()
                .filter(job -> "MATCHED".equals(job.getResult()))
                .map(RuleExecutionJob::getRule)
                .toList();
        if (matchedRules.isEmpty()) {
            setFinalStatus(stored, MessageStatus.PROCESSED);
            return;
        }

        var input = createInput(stored, matchedRules);
        if (ruleEngine.shouldFilter(input, matchedRules)) {
            setFinalStatus(stored, MessageStatus.PROCESSED);
            return;
        }

        String target = ruleEngine.determineTargetChannel(input, matchedRules);
        if (target != null) {
            Rule routingRule = matchedRules.stream().filter(rule -> rule.getActions().stream()
                    .anyMatch(action -> action.getActionType() == ActionType.ROUTE)).findFirst().orElseThrow();
            var destination = com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService.target(routingRule,
                    channels);
            var transformed = ruleEngine.applyTransformations(input, matchedRules);
            var outbound = new Message(MessageDirection.OUTBOUND, "", stored.getPayloadType());
            String output = PayloadCodec.charset((String) transformed.getHeaders().get("outputCharset"));
            if (output != null && transformed.getPayload() instanceof byte[])
                throw new IllegalArgumentException("Output charset requires a decoded text payload");
            boolean changed = transformed.getPayload() != input.getPayload() || output != null;
            if (changed && output == null) output = PayloadCodec.charset(destination.getProperties().get("outputCharset"));
            if (changed && output == null) output = stored.getCharset() == null ? "UTF-8" : stored.getCharset();
            outbound.setContent(changed ? RulePayloads.encode(transformed.getPayload(), output, objectMapper) : stored.getPayloadBytes(),
                    changed ? output : stored.getCharset(), changed ? "RULE" : stored.getCharsetSource(), "BASE64");
            transformed.getHeaders().forEach((key, value) -> {
                if (!Set.of("id", "timestamp", "encoding", "charset").contains(key)
                        && (value instanceof String || value instanceof Number || value instanceof Boolean)) outbound.addMetadata(key, value.toString());
            });
            outbound.addMetadata("encoding", "base64");
            if (outbound.getCharset() != null) outbound.addMetadata("charset", outbound.getCharset());
            outbound.addMetadata("sourceMessageId",stored.getId().toString());
            outbound.setPayloadType(changed ? PayloadCodec.outputType(stored.getPayloadType(),output) : stored.getPayloadType());
            outbound.addMetadata("payloadType",outbound.getPayloadType());
            outbound.setSourceSystem(stored.getSourceSystem());
            outbound.setTargetSystem(destination.getName());
            var metadata = new TreeMap<String, String>();
            outbound.getMetadata().forEach(value -> metadata.put(value.getKey(), value.getValue()));
            metadata.remove("encoding");
            metadata.put("m3RuleId", routingRule.getId().toString());
            var plan = new com.sysadminanywhere.m3.messaging.outbound.PreparedOutboundDelivery(destination.getId(),
                    outbound.getPayloadBytes(), outbound.getPayloadType(), metadata);
            String serializedPlan = objectMapper.writeValueAsString(plan);
            messages.save(outbound);
            var deliveryJob = new RuleExecutionJob(outbound, routingRule, routingRule.getWorkerPool());
            deliveryJob.setResult(serializedPlan);
            jobs.save(deliveryJob);
            stored.setTargetSystem(destination.getName());
        }
        setFinalStatus(stored, MessageStatus.PROCESSED);
    }


    private void setFinalStatus(Message message, MessageStatus status) {
        message.setStatus(status);
        message.setProcessedAt(java.time.Instant.now());
        messages.save(message);
    }

    private org.springframework.messaging.Message<Object> createInput(Message stored, List<Rule> rules) throws Exception {
        Object payload = RulePayloads.readForRules(stored, objectMapper, rules);
        Map<String, Object> headers = new HashMap<>();
        headers.put("channelName", stored.getSourceSystem());
        headers.put("sourceSystem", stored.getSourceSystem());
        stored.getMetadata().forEach(metadata -> headers.put(metadata.getKey(), metadata.getValue()));
        return org.springframework.messaging.support.MessageBuilder.withPayload(payload).copyHeaders(headers).build();
    }

}
