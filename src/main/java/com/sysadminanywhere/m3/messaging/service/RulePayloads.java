package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.PayloadCodec;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.domain.ActionType;
import java.util.List;
import java.util.Locale;

public final class RulePayloads {
    private RulePayloads() { }
    public static Object readForRules(Message stored, ObjectMapper json, List<Rule> rules) throws Exception {
        boolean textNeeded = rules.stream().anyMatch(rule -> rule.getConditions().stream().anyMatch(condition -> condition.getField().startsWith("payload"))
                || rule.getActions().stream().anyMatch(action -> action.getActionType() == ActionType.TRANSFORM && action.getTransformationScript() != null));
        boolean reencode = rules.stream().flatMap(rule -> rule.getActions().stream())
                .anyMatch(action -> action.getActionType() == ActionType.ENRICH && "outputCharset".equals(action.getMetadataKey()));
        if (!textNeeded && !reencode) return stored.getPayloadBytes();
        if (stored.getCharset() == null) throw new IllegalArgumentException("Text rules require a known source charset");
        if (textNeeded) return read(stored, json);
        if (PayloadCodec.bomConflicts(stored.getPayloadBytes(), PayloadCodec.charset(stored.getCharset())))
            throw new IllegalArgumentException("BOM conflicts with the stored charset");
        String text = stored.getDecodedText();
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }
    public static Object read(Message stored, ObjectMapper json) throws Exception {
        if (stored.getCharset() == null) return stored.getPayloadBytes();
        if (PayloadCodec.bomConflicts(stored.getPayloadBytes(), PayloadCodec.charset(stored.getCharset())))
            throw new IllegalArgumentException("BOM conflicts with the stored charset");
        String text = stored.getDecodedText();
        // A BOM is a byte detail of the original, not part of JSON syntax.
        if (text.startsWith("\ufeff")) text = text.substring(1);
        String mediaType = stored.getPayloadType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("json") || mediaType.endsWith("/json") || mediaType.endsWith("+json")
                ? json.readValue(text, Object.class) : text;
    }
    public static byte[] encode(Object payload, String charset, ObjectMapper json) throws Exception {
        if (payload instanceof byte[] bytes) return bytes;
        return PayloadCodec.encode(payload instanceof String text ? text : json.writeValueAsString(payload), charset);
    }
}
