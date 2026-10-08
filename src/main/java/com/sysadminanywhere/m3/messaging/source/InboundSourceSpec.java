package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import java.util.Map;

public record InboundSourceSpec(long id, String name, ChannelType type, Map<String, String> properties, Long ruleId) {
    public InboundSourceSpec(long id, String name, ChannelType type, Map<String, String> properties) { this(id, name, type, properties, null); }
    public static final java.util.Set<String> LOADING_KEYS = java.util.Set.of("pollingInterval", "filePattern", "recursive", "minFileAgeMs",
            "deleteAfterProcessing", "deleteRemoteFiles", "groupId", "autoOffsetReset", "initialStatus", "processingRecipients", "loadedTimeoutSeconds", "processingTimeoutSeconds");
    public static InboundSourceSpec fromRule(com.sysadminanywhere.m3.messaging.domain.Rule rule) {
        var channel = rule.getSourceChannel();
        var settings = new java.util.HashMap<>(channel.getProperties());
        LOADING_KEYS.forEach(settings::remove);
        rule.getLoadingProperties().forEach((key,value) -> { if (LOADING_KEYS.contains(key) && value != null && !value.isBlank()) settings.put(key,value); });
        settings.putIfAbsent("groupId", "m3-rule-" + rule.getId());
        settings.put("_workerPool", rule.getWorkerPool().getName());
        return new InboundSourceSpec(channel.getId(), channel.getName(), channel.getChannelType(), Map.copyOf(settings), rule.getId());
    }
    public static InboundSourceSpec from(ChannelSettings channel) {
        return new InboundSourceSpec(channel.getId()==null ? 0 : channel.getId(), channel.getName(), channel.getChannelType(), Map.copyOf(channel.getProperties()));
    }
    public long runtimeId() { return ruleId == null ? id : ruleId; }
    public String value(String key, String fallback) { return properties.getOrDefault(key, fallback); }
    public String required(String key) {
        String value = value(key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required for " + type);
        return value;
    }
    public int number(String key, int fallback) {
        int value = Integer.parseInt(value(key, Integer.toString(fallback)));
        if (value < 1) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
    public boolean flag(String key, boolean fallback) { return Boolean.parseBoolean(value(key, Boolean.toString(fallback))); }
    // Do not include connection credentials in logs or exceptions.
    @Override public String toString() { return "InboundSource[" + id + ", " + name + ", " + type + "]"; }
}
