package com.sysadminanywhere.m3.messaging.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.MessageMetadata;
import java.util.*;

/** Preserve header order, duplicate names, nulls and binary values in a lossless envelope. */
public final class ProtocolMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProtocolMetadata() { }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Could not preserve protocol metadata",error);
        }
    }
    public static Map<String,Object> header(String name,Object value) {
        var result = new LinkedHashMap<String,Object>();
        result.put("name",name);
        result.put("type",value == null ? "NULL" : value instanceof byte[] ? "BYTES" : value.getClass().getName());
        result.put("value",normalize(value));
        return result;
    }
    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof java.util.Date date) return java.time.Instant.ofEpochMilli(date.getTime()).toString();
        if (value instanceof com.rabbitmq.client.LongString string) return Base64.getEncoder().encodeToString(string.getBytes());
        if (value.getClass().isArray()) {
            var values = new ArrayList<Object>();
            for (int index=0; index<java.lang.reflect.Array.getLength(value); index++)
                values.add(header("",java.lang.reflect.Array.get(value,index)));
            return values;
        }
        if (value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<String,Object>();
            map.forEach((key,item) -> result.put(key.toString(),header(key.toString(),item)));
            return result;
        }
        if (value instanceof Collection<?> list) return list.stream().map(item -> header("",item)).toList();
        return value.toString();
    }
    public static void scalar(Map<String,String> metadata,String key,String value) {
        if (metadata.size()<80 && key.length() <= MessageMetadata.KEY_MAX_LENGTH && value.length() <= MessageMetadata.VALUE_MAX_LENGTH)
            metadata.put(key,value);
    }
}
