package com.sysadminanywhere.m3.messaging.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.*;
import com.sysadminanywhere.m3.messaging.service.RuleEngine;
import com.sysadminanywhere.m3.messaging.service.RulePayloads;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class OutboundDeliveryTransactions {
    public record Claim(long jobId, long messageId, UUID token, PreparedOutboundDelivery delivery) { }
    private final RuleExecutionJobRepository jobs;
    private final ChannelSettingsRepository channels;
    private final RuleEngine engine;
    private final ObjectMapper json;
    private final String pool;
    private final String worker;
    private final int maxAttempts;
    @PersistenceContext private EntityManager entityManager;
    public OutboundDeliveryTransactions(RuleExecutionJobRepository jobs, ChannelSettingsRepository channels, RuleEngine engine, ObjectMapper json,
            @Value("${m3.worker.pool:default}") String pool, @Value("${HOSTNAME:local-worker}") String worker,
            @Value("${m3.outbound.max-attempts:20}") int maxAttempts) {
        this.jobs = jobs; this.channels = channels; this.engine = engine; this.json = json;
        this.pool = pool; this.worker = worker; this.maxAttempts = maxAttempts;
    }

    @Transactional
    public Claim claim() {
        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery("""
                SELECT j.job_id FROM rule_execution_job j
                JOIN rule r ON r.rule_id=j.rule_id
                JOIN rule_worker_pool p ON p.worker_pool_id=r.worker_pool_id
                JOIN message m ON m.message_id=j.message_id
                WHERE p.name=:pool AND j.status='PENDING' AND j.next_attempt_at<=now() AND m.direction='OUTBOUND'
                ORDER BY j.created_at,j.job_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                """).setParameter("pool", pool).getResultList();
        if (ids.isEmpty()) return null;
        var job = jobs.findById(ids.getFirst().longValue()).orElseThrow();
        job.reassignPool(job.getRule().getWorkerPool());
        try {
            if (!Boolean.TRUE.equals(job.getRule().getEnabled()) || (job.getResult() == null && job.getRule().getRuleType() != RuleType.OUTBOUND))
                throw new IllegalStateException("Outbound rule is disabled or changed type");
            var plan = job.getResult() == null ? prepare(job) : json.readValue(job.getResult(), PreparedOutboundDelivery.class);
            if (job.getResult() == null) job.setResult(json.writeValueAsString(plan));
            job.claim(worker);
            return new Claim(job.getId(), job.getMessageId(), job.getClaimToken(), plan);
        } catch (Exception error) {
            job.fail(error);
            finish(job.getMessage(), MessageStatus.FAILED);
            return null;
        }
    }

    private PreparedOutboundDelivery prepare(RuleExecutionJob job) throws Exception {
        var stored = job.getMessage(); var rule = job.getRule();
        if (!Boolean.TRUE.equals(rule.getEnabled()) || rule.getRuleType() != RuleType.OUTBOUND)
            throw new IllegalStateException("Outbound rule is disabled or changed type");
        var target = OutboundSubmissionService.target(rule, channels);
        Map<String, Object> headers = new HashMap<>();
        stored.getMetadata().forEach(value -> headers.put(value.getKey(), value.getValue()));
        headers.put("sourceSystem", stored.getSourceSystem()); headers.put("channelName", stored.getSourceSystem());
        headers.put("m3MessageId", stored.getId()); headers.put("m3RuleId", rule.getId());
        if (stored.getCharset() != null) headers.put("charset", stored.getCharset());
        Object payload = RulePayloads.readForRules(stored, json, List.of(rule));
        var input = org.springframework.messaging.support.MessageBuilder.withPayload(payload).copyHeaders(headers).build();
        if (!engine.evaluateConditions(rule, input) || engine.shouldFilter(input, List.of(rule)))
            throw new IllegalStateException("Message does not satisfy the selected outbound rule");
        boolean sendMessageId = rule.getOutboundPayloadMode() == OutboundPayloadMode.MESSAGE_ID;
        var transformed = sendMessageId ? engine.applyMetadataActions(input, List.of(rule))
                : engine.applyTransformations(input, List.of(rule));
        Object transformedPayload = transformed.getPayload();
        String output = PayloadCodec.charset((String) transformed.getHeaders().get("outputCharset"));
        if (!sendMessageId && output != null && transformedPayload instanceof byte[])
            throw new IllegalArgumentException("Output charset requires a decoded text payload; source charset is unknown");
        boolean changed = transformedPayload != input.getPayload() || output != null;
        if (changed && output == null) output = PayloadCodec.charset(target.getProperties().get("outputCharset"));
        if (changed && output == null) output = stored.getCharset() == null ? "UTF-8" : stored.getCharset();
        byte[] bytes = sendMessageId ? Long.toString(stored.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : changed ? RulePayloads.encode(transformedPayload, output, json) : stored.getPayloadBytes();
        var metadata = new TreeMap<String, String>();
        transformed.getHeaders().forEach((key, value) -> {
            if (!Set.of("id", "timestamp", "encoding").contains(key)
                    && (value instanceof String || value instanceof Number || value instanceof Boolean)) metadata.put(key, value.toString());
        });
        metadata.put("m3MessageId", stored.getId().toString());
        metadata.put("m3RuleId", rule.getId().toString());
        metadata.remove("charset");
        if (sendMessageId) {
            metadata.put("m3MessagePayloadPath", "/api/v1/messages/" + stored.getId() + "/payload");
            metadata.put("charset", "UTF-8");
        } else {
            if (!changed && stored.getCharset() != null) metadata.put("charset", stored.getCharset());
            if (changed && !(transformedPayload instanceof byte[])) metadata.put("charset", output);
        }
        stored.setTargetSystem(target.getName());
        return new PreparedOutboundDelivery(target.getId(), bytes,
                sendMessageId ? "text/plain" : changed ? PayloadCodec.outputType(stored.getPayloadType(), output) : stored.getPayloadType(), metadata);
    }

    @Transactional
    public void succeeded(Claim claim) {
        var job = locked(claim);
        if (job == null) return;
        job.complete();
        finish(job.getMessage(), MessageStatus.SENT);
    }

    @Transactional
    public void failed(Claim claim, Exception error) {
        var job = locked(claim);
        if (job == null) return;
        if (job.getAttempts() >= maxAttempts || error instanceof IllegalArgumentException) {
            job.fail(error); finish(job.getMessage(), MessageStatus.FAILED);
        } else job.retry(error, Math.min(60, 1L << Math.min(job.getAttempts(), 5)));
    }

    private RuleExecutionJob locked(Claim claim) {
        var job = entityManager.find(RuleExecutionJob.class, claim.jobId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return job != null && job.getStatus() == RuleJobStatus.PROCESSING && claim.token().equals(job.getClaimToken()) ? job : null;
    }
    private static void finish(Message message, MessageStatus status) { message.setStatus(status); message.setProcessedAt(Instant.now()); }
}
