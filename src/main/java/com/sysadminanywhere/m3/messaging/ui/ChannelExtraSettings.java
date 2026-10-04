package com.sysadminanywhere.m3.messaging.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.source.InboundSourceSpec;
import com.vaadin.flow.component.textfield.TextArea;
import java.util.*;

final class ChannelExtraSettings extends TextArea {
    private static final ObjectMapper JSON=new ObjectMapper();
    private Set<String> visible=Set.of();
    ChannelExtraSettings() {
        super("Additional connection properties (JSON)");
        setWidthFull(); setHeight("160px");
        setHelperText("Additional protocol settings, for example kafka.security.protocol. Loading policy belongs to the rule.");
    }
    void load(Map<String,String> properties,Set<String> visible) {
        this.visible=Set.copyOf(visible);
        var extra=new TreeMap<String,String>(properties);
        visible.forEach(extra::remove); InboundSourceSpec.LOADING_KEYS.forEach(extra::remove);
        extra.remove("keyDeserializer"); extra.remove("valueDeserializer");
        try { setValue(extra.isEmpty() ? "" : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(extra)); }
        catch (Exception error) { throw new IllegalArgumentException("Could not display connection properties",error); }
    }
    Map<String,String> settings() {
        var result=new HashMap<String,String>();
        if (getValue().isBlank()) return result;
        try {
            var root=JSON.readTree(getValue());
            if (!root.isObject()) throw new IllegalArgumentException("Additional properties must be a JSON object");
            var fields=root.properties().iterator();
            while (fields.hasNext()) {
                var entry=fields.next();
                if (entry.getKey().isBlank() || entry.getKey().length()>255 || visible.contains(entry.getKey())
                        || InboundSourceSpec.LOADING_KEYS.contains(entry.getKey())
                        || Set.of("keyDeserializer","valueDeserializer").contains(entry.getKey()))
                    throw new IllegalArgumentException("Use the dedicated field or rule settings for: "+entry.getKey());
                if (!entry.getValue().isValueNode() || entry.getValue().isNull())
                    throw new IllegalArgumentException("Connection property values must be strings, numbers or booleans");
                result.put(entry.getKey(),entry.getValue().asText());
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid additional connection properties JSON");
        }
    }
}
