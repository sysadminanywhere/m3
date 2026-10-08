package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.PayloadCodec;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import com.sysadminanywhere.m3.messaging.source.InboundSourceSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Service
public class SourceDeliveryService {
    private final InboundMessageEnqueueService inbound;
    private final JdbcTemplate jdbc;
    private final ChannelSettingsRepository channels;
    private final com.sysadminanywhere.m3.messaging.repository.RuleRepository rules;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public SourceDeliveryService(InboundMessageEnqueueService inbound, JdbcTemplate jdbc, ChannelSettingsRepository channels,
            com.sysadminanywhere.m3.messaging.repository.RuleRepository rules, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.inbound = inbound;
        this.jdbc = jdbc;
        this.channels = channels;
        this.rules = rules;
        this.json = json;
    }
    @Transactional
    public long receive(InboundSourceSpec source, Object payload, String payloadType, Map<String, String> metadata, String deliveryKey) {
        metadata = new java.util.HashMap<>(metadata == null ? Map.of() : metadata);
        boolean raw = payload instanceof byte[];
        String declaredType = payloadType;
        if (raw && (payloadType == null || payloadType.isBlank() || payloadType.length() > com.sysadminanywhere.m3.messaging.domain.Message.PAYLOAD_TYPE_MAX_LENGTH)) {
            if (payloadType != null) metadata.put("declaredPayloadType",payloadType);
            payloadType="application/octet-stream";
        }
        var channel = channels.findById(source.id()).orElseThrow(() -> new IllegalStateException("Source was deleted"));
        if (channel.getDirection() != ChannelDirection.INBOUND) throw new IllegalStateException("Invalid source direction");
        if (source.ruleId() != null) {
            var rule = rules.findById(source.ruleId()).orElseThrow(() -> new IllegalStateException("Loading rule was deleted"));
            if (!Boolean.TRUE.equals(rule.getEnabled()) || rule.getRuleType() != com.sysadminanywhere.m3.messaging.domain.RuleType.INBOUND
                    || rule.getWorkerPool() == null || !InboundSourceSpec.fromRule(rule).equals(source))
                throw new IllegalStateException("Loading rule configuration changed");
            com.sysadminanywhere.m3.messaging.source.LoadingPolicy.validate(rule, rules.findBySourceChannel(channel));
        } else throw new IllegalArgumentException("Ingestion requires an explicit loading rule");
        String key = deliveryKey == null ? null : digest(((source.ruleId() == null ? "" : "rule:" + source.ruleId() + ":") + deliveryKey).getBytes(StandardCharsets.UTF_8));
        String requestDigest = null;
        if (deliveryKey != null && deliveryKey.startsWith("http:")) {
            try {
                requestDigest = digest(json.writeValueAsBytes(java.util.List.of(payloadType,
                        payload instanceof byte[] ? "BYTES" : "TEXT", payload, new java.util.TreeMap<>(metadata))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalArgumentException("Could not encode ingestion request", error);
            }
        }
        if (key != null) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, source.id() + ":" + key);
            var previous = jdbc.query("SELECT message_id,request_digest FROM source_delivery_receipt WHERE channel_id=? AND delivery_key=?",
                    (rs, row) -> new Receipt(rs.getLong(1),rs.getString(2)), source.id(), key);
            if (!previous.isEmpty()) {
                var receipt = previous.getFirst();
                if (receipt.digest() != null && !receipt.digest().equals(requestDigest))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                            "Idempotency-Key was used with different message data");
                return receipt.messageId();
            }
        }
        String requested;
        try { requested=PayloadCodec.charset(metadata.get("charset")); }
        catch (IllegalArgumentException invalid) {
            if (!raw) throw invalid;
            metadata.put("declaredCharset",metadata.remove("charset"));
            metadata.put("charsetDeclarationError","Unsupported protocol charset");
            requested=null;
        }
        String media;
        try { media=declaredType==null ? null : PayloadCodec.mediaCharset(declaredType); }
        catch (IllegalArgumentException invalid) {
            if (!raw) throw invalid;
            metadata.put("charsetDeclarationError","Unsupported charset in protocol content type");
            media=null;
        }
        boolean conflict=requested!=null && media!=null && !requested.equals(media);
        if (conflict && !raw) throw new IllegalArgumentException("Conflicting charset declarations");
        String charset=conflict ? null : requested==null ? media : requested;
        String origin=raw ? "PROTOCOL" : "REQUEST";
        if (conflict) {
            metadata.put("declaredCharset",metadata.remove("charset"));
            metadata.put("charsetDeclarationError","Conflicting protocol charset declarations");
            origin="DECLARATION_CONFLICT";
        } else if (charset==null) {
            charset=PayloadCodec.charset(source.value("charset",""));
            origin=charset!=null ? "CHANNEL" : metadata.containsKey("charsetDeclarationError") ? "UNSUPPORTED_PROTOCOL" : "UNKNOWN";
        }
        var input = MessageBuilder.withPayload(payload).setHeader("sourceSystem", source.name())
                .setHeader("channelName", source.name()).setHeader("sourceChannelId", source.id())
                .setHeader("channelType", source.type().name()).setHeader("payloadType", payloadType)
                .setHeader("metadata", metadata).setHeader("payloadCharset", charset)
                .setHeader("loadingRuleId", source.ruleId())
                .setHeader("payloadCharsetSource", origin).build();
        var saved = inbound.enqueue(input.getPayload(), input.getHeaders());
        if (key != null) jdbc.update("INSERT INTO source_delivery_receipt(channel_id,delivery_key,message_id,request_digest) VALUES(?,?,?,?)",
                source.id(), key, saved.getId(), requestDigest);
        return saved.getId();
    }
    private record Receipt(long messageId, String digest) { }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
