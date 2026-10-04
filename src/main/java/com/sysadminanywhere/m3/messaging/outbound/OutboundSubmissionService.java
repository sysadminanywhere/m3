package com.sysadminanywhere.m3.messaging.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.*;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class OutboundSubmissionService {
    private final RuleRepository rules;
    private final ChannelSettingsRepository channels;
    private final MessageRepository messages;
    private final RuleExecutionJobRepository jobs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    public OutboundSubmissionService(RuleRepository rules, ChannelSettingsRepository channels, MessageRepository messages,
                                     RuleExecutionJobRepository jobs, JdbcTemplate jdbc, ObjectMapper json) {
        this.rules = rules; this.channels = channels; this.messages = messages; this.jobs = jobs; this.jdbc = jdbc; this.json = json;
    }

    public record ForwardingRule(long ruleId, String ruleName, long channelId, String channelName) { }

    @Transactional(readOnly = true)
    public List<ForwardingRule> forwardingRules() {
        var available = new ArrayList<ForwardingRule>();
        for (var rule : rules.findByRuleType(RuleType.OUTBOUND)) {
            if (!Boolean.TRUE.equals(rule.getEnabled()) || rule.getWorkerPool() == null) continue;
            try {
                var destination = target(rule, channels);
                available.add(new ForwardingRule(rule.getId(), rule.getName(), destination.getId(), destination.getName()));
            } catch (ResponseStatusException unavailable) { /* Incomplete rules cannot deliver a copy. */ }
        }
        available.sort(Comparator.comparing(ForwardingRule::channelName).thenComparing(ForwardingRule::ruleName).thenComparingLong(ForwardingRule::ruleId));
        return available;
    }

    /** Persist a byte-for-byte snapshot and its job without changing the source message. */
    @Transactional
    public Message forward(long sourceMessageId, long ruleId, String requestKey) {
        if (requestKey != null && (requestKey.isBlank() || requestKey.length() > 200))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-200 characters");
        String digest = SourceDeliveryService.digest(("forward:" + sourceMessageId).getBytes(StandardCharsets.UTF_8));
        String key = requestKey == null ? null : SourceDeliveryService.digest(requestKey.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, "outbound:" + ruleId + ":" + key);
            var existing = jdbc.query("SELECT message_id,request_digest FROM outbound_message_request WHERE rule_id=? AND request_key=?",
                    (rs, row) -> Map.entry(rs.getLong(1), rs.getString(2)), ruleId, key);
            if (!existing.isEmpty()) {
                if (!digest.equals(existing.getFirst().getValue()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used with different data");
                return messages.findById(existing.getFirst().getKey()).orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Forwarded message was deleted"));
            }
        }
        var source = entityManager.find(Message.class, sourceMessageId, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        if (source == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source message not found");
        var rule = rules.findById(ruleId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (rule.getRuleType() != RuleType.OUTBOUND || !Boolean.TRUE.equals(rule.getEnabled()) || rule.getWorkerPool() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule must be enabled, OUTBOUND and assigned to a worker pool");
        var destination = target(rule, channels);
        var copy = new Message(MessageDirection.OUTBOUND, "", source.getPayloadType());
        copy.setContent(source.getPayloadBytes(), source.getCharset(), source.getCharsetSource(), source.getPayloadFormat());
        copy.setSourceSystem(source.getDirection() == MessageDirection.OUTBOUND && source.getTargetSystem() != null
                ? source.getTargetSystem() : source.getSourceSystem());
        copy.setTargetSystem(destination.getName());
        source.getMetadata().forEach(value -> copy.addMetadata(value.getKey(), value.getValue()));
        copy.addMetadata("sourceMessageId", Long.toString(sourceMessageId));
        copy.addMetadata("outboundRuleId", Long.toString(ruleId));
        // Rule headers must describe this new delivery rather than a previous copy.
        messages.save(copy);
        copy.addMetadata("m3MessageId", copy.getId().toString());
        copy.addMetadata("m3RuleId", Long.toString(ruleId));
        jobs.save(new RuleExecutionJob(copy, rule, rule.getWorkerPool()));
        if (key != null) jdbc.update("INSERT INTO outbound_message_request(rule_id,request_key,message_id,request_digest) VALUES(?,?,?,?)",
                ruleId, key, copy.getId(), digest);
        return copy;
    }

    @Transactional
    public Message submit(long ruleId, String payload, String payloadType, String charset, Map<String, String> metadata, String requestKey) {
        Map<String, String> values = metadata == null ? Map.of() : metadata;
        validate(payload, payloadType, values, requestKey);
        var message = new Message(MessageDirection.OUTBOUND, "", payloadType);
        try { PayloadCodec.applyRequest(message, payload, charset, values); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        String digest;
        try { digest = SourceDeliveryService.digest(json.writeValueAsBytes(List.of(payload, payloadType, charset == null ? "" : charset, new TreeMap<>(values)))); }
        catch (Exception error) { throw new IllegalArgumentException("Could not encode request", error); }
        String key = requestKey == null ? null : SourceDeliveryService.digest(requestKey.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, "outbound:" + ruleId + ":" + key);
            var existing = jdbc.query("SELECT message_id,request_digest FROM outbound_message_request WHERE rule_id=? AND request_key=?",
                    (rs, row) -> Map.entry(rs.getLong(1), rs.getString(2)), ruleId, key);
            if (!existing.isEmpty()) {
                if (!digest.equals(existing.getFirst().getValue())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was used with different data");
                return messages.findById(existing.getFirst().getKey()).orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Message was deleted"));
            }
        }
        var rule = rules.findById(ruleId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found"));
        if (rule.getRuleType() != RuleType.OUTBOUND || !Boolean.TRUE.equals(rule.getEnabled()) || rule.getWorkerPool() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule must be enabled, OUTBOUND and assigned to a worker pool");
        var target = target(rule, channels);
        message.setSourceSystem(rule.getSourceChannel().getName()); message.setTargetSystem(target.getName());
        values.forEach(message::addMetadata);
        message.addMetadata("outboundRuleId", Long.toString(ruleId));
        messages.save(message);
        jobs.save(new RuleExecutionJob(message, rule, rule.getWorkerPool()));
        if (key != null) jdbc.update("INSERT INTO outbound_message_request(rule_id,request_key,message_id,request_digest) VALUES(?,?,?,?)",
                ruleId, key, message.getId(), digest);
        return message;
    }

    public static ChannelSettings target(Rule rule, ChannelSettingsRepository channels) {
        var routes = rule.getActions().stream().filter(action -> action.getActionType() == ActionType.ROUTE).toList();
        if (routes.size() != 1 || routes.getFirst().getTargetChannel() == null || routes.getFirst().getTargetChannel().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Outbound rule must have exactly one ROUTE action");
        var route = routes.getFirst();
        var target = route.getDestinationChannel() == null
                ? channels.findByName(route.getTargetChannel()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Target channel not found"))
                : channels.findById(route.getDestinationChannel().getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Target channel not found"));
        if (target.getDirection() != ChannelDirection.OUTBOUND || !Boolean.TRUE.equals(target.getEnabled()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Target channel must be enabled and OUTBOUND");
        return target;
    }

    private static void validate(String payload, String type, Map<String, String> metadata, String key) {
        if (payload == null || payload.length() > Message.PAYLOAD_REQUEST_MAX_LENGTH || type == null || type.isBlank() || type.length() > Message.PAYLOAD_TYPE_MAX_LENGTH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payload or payloadType");
        if (metadata.size() > 100 || metadata.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getKey().length() > MessageMetadata.KEY_MAX_LENGTH || entry.getValue() == null || entry.getValue().length() > MessageMetadata.VALUE_MAX_LENGTH
                || Set.of("outboundRuleId", "m3MessageId", "m3RuleId", "id", "timestamp").contains(entry.getKey())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or reserved metadata key/value");
        if (key != null && (key.isBlank() || key.length() > 200)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-200 characters");
        if (metadata.containsKey("encoding")) {
            if (!metadata.get("encoding").equals("base64")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only base64 binary encoding is supported");
            try { Base64.getDecoder().decode(payload); }
            catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Base64 payload"); }
        }
    }
}
