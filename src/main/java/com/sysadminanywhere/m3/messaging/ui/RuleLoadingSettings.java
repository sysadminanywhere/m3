package com.sysadminanywhere.m3.messaging.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.source.InboundSourceSpec;
import com.vaadin.flow.component.textfield.TextArea;
import java.util.*;

final class RuleLoadingSettings extends TextArea {
    private final ObjectMapper json = new ObjectMapper();
    RuleLoadingSettings() {
        super("Loading settings"); setWidthFull(); setMinHeight("120px");
        setHelperText("JSON: pollingInterval (ms), filePattern, minFileAgeMs, recursive, deleteAfterProcessing / deleteRemoteFiles; Kafka: groupId, autoOffsetReset. Empty uses defaults.");
        setPlaceholder("{\"pollingInterval\":5000,\"filePattern\":\"*.xml\",\"deleteAfterProcessing\":false}");
    }
    void load(Map<String,String> values) {
        try { setValue(json.writerWithDefaultPrettyPrinter().writeValueAsString(values)); }
        catch (Exception error) { throw new IllegalArgumentException("Could not load loading settings", error); }
    }
    Map<String,String> settings() {
        if (!isVisible() || getValue().isBlank()) return Map.of();
        try {
            Map<String,Object> values = json.readValue(getValue(), new TypeReference<>() {});
            Map<String,String> result = new HashMap<>();
            for (var entry : values.entrySet()) {
                if (!InboundSourceSpec.LOADING_KEYS.contains(entry.getKey()) || entry.getValue() == null
                        || !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean))
                    throw new IllegalArgumentException("Unsupported loading setting: " + entry.getKey());
                result.put(entry.getKey(), entry.getValue().toString());
            }
            return result;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Loading settings must be a JSON object", error); }
    }
}
