package com.sysadminanywhere.m3.messaging.api;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService;
import com.sysadminanywhere.m3.messaging.repository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.LockModeType;

@RestController
@RequestMapping("/api/v1")
@Profile("!worker")
public class OutboundMessageApi {
    private final OutboundSubmissionService submissions;
    private final MessageRepository messages;
    private final RuleExecutionJobRepository jobs;
    private final RuleRepository rules;
    public OutboundMessageApi(OutboundSubmissionService submissions, MessageRepository messages, RuleExecutionJobRepository jobs, RuleRepository rules) {
        this.submissions = submissions; this.messages = messages; this.jobs = jobs; this.rules = rules;
    }
    @PostMapping("/messages/outbound")
    @Transactional
    public ResponseEntity<MessageApi.MessageResponse> submit(@Valid @RequestBody Request request,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        var stored = submissions.submit(request.ruleId(), request.payload(), request.payloadType(), request.charset(), request.metadata(), key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/messages/" + stored.getId())).body(MessageApi.response(stored));
    }
    @PostMapping("/messages/{id}/forward")
    @Transactional
    public ResponseEntity<MessageApi.MessageResponse> forward(@PathVariable long id, @Valid @RequestBody ForwardRequest request,
            @RequestHeader(name="Idempotency-Key", required=false) String key) {
        var stored = submissions.forward(id, request.ruleId(), key);
        return ResponseEntity.accepted().location(URI.create("/api/v1/messages/" + stored.getId())).body(MessageApi.response(stored));
    }
    @GetMapping("/messages/{id}/delivery")
    @Transactional(readOnly=true)
    public List<DeliveryStatus> status(@PathVariable long id) {
        outbound(id);
        return jobs.findByMessage_IdOrderByRule_PriorityAsc(id).stream().map(job -> new DeliveryStatus(job.getId(), job.getRule().getId(),
                job.getWorkerPool().getName(), job.getStatus(), job.getAttempts(), job.getNextAttemptAt(), job.getErrorMessage())).toList();
    }
    @GetMapping("/rules/outbound")
    @Transactional(readOnly=true)
    public List<RuleInfo> rules() {
        return rules.findByRuleType(RuleType.OUTBOUND).stream().map(rule -> new RuleInfo(rule.getId(), rule.getName(),
                Boolean.TRUE.equals(rule.getEnabled()), rule.getWorkerPool() == null ? null : rule.getWorkerPool().getName())).toList();
    }
    @GetMapping("/rules/forwarding")
    public List<OutboundSubmissionService.ForwardingRule> forwardingRules() {
        return submissions.forwardingRules();
    }
    private void outbound(long id) {
        if (messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).getDirection() != MessageDirection.OUTBOUND)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Message is not outbound");
    }
    public record Request(@NotNull @Positive Long ruleId, @NotNull @Size(max=Message.PAYLOAD_REQUEST_MAX_LENGTH) String payload,
            @NotBlank @Size(max=Message.PAYLOAD_TYPE_MAX_LENGTH) String payloadType,
            @Size(max=64) String charset,
            @Size(max=100) Map<@NotBlank @Size(max=MessageMetadata.KEY_MAX_LENGTH) String,
                    @NotNull @Size(max=MessageMetadata.VALUE_MAX_LENGTH) String> metadata) { }
    public record DeliveryStatus(Long jobId, Long ruleId, String workerPool, RuleJobStatus status, int attempts, Instant nextAttemptAt, String error) { }
    public record RuleInfo(Long id, String name, boolean enabled, String workerPool) { }
    public record ForwardRequest(@NotNull @Positive Long ruleId) { }
}
