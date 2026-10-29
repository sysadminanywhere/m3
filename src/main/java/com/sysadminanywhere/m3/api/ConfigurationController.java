package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/v2/configuration")
public class ConfigurationController {
    private final JdbcTemplate jdbc;
    public ConfigurationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/schema")
    public Map<String, Object> schema() { return Map.of("formatVersion", "2", "service", "m3-community", "status", "supported"); }
    @GetMapping("/version")
    public Map<String, Integer> version() { return Map.of("version", jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class)); }

    @PostMapping("/validate")
    public ValidationResponse validate(@RequestBody JsonNode document) { return validateDocument(document); }

    @PostMapping("/publish")
    @Transactional
    public ResponseEntity<?> publish(@RequestBody PublishRequest request) {
        ValidationResponse validation = validateDocument(request.document());
        if (!validation.errors().isEmpty()) return ResponseEntity.badRequest().body(validation);
        Integer current = jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class);
        int expected = request.expectedVersion() == null ? current : request.expectedVersion();
        if (expected != current) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "configuration_version_conflict", "currentVersion", current));
        int next = current + 1;
        jdbc.update("insert into community.config_bundle(version, document_json) values (?, cast(? as jsonb))", next, request.document().toString());
        return ResponseEntity.ok(Map.of("version", next, "status", "published", "warnings", validation.warnings()));
    }

    private ValidationResponse validateDocument(JsonNode d) {
        List<String> errors = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        if (d == null || !d.isObject()) errors.add("$: document must be an object");
        else {
            if (!d.has("formatVersion")) errors.add("$.formatVersion: required");
            else if (!"2".equals(d.get("formatVersion").asText())) errors.add("$.formatVersion: must be '2'");
            for (String key : List.of("connections", "sources", "recipients", "rules")) if (d.has(key) && !d.get(key).isArray()) errors.add("$." + key + ": must be an array");
            Set<String> sources = identifiers(d.path("sources"), "sources", errors);
            Set<String> recipients = identifiers(d.path("recipients"), "recipients", errors);
            for (int i = 0; i < d.path("rules").size(); i++) {
                JsonNode rule = d.path("rules").get(i);
                for (JsonNode source : rule.path("sourceIds")) if (!sources.contains(source.asText())) errors.add("$.rules[" + i + "].sourceIds: unknown source '" + source.asText() + "'");
                String recipient = rule.path("recipientId").asText();
                if (!recipient.isBlank() && !recipients.contains(recipient)) errors.add("$.rules[" + i + "].recipientId: unknown recipient '" + recipient + "'");
            }
            if (!d.has("rules") || d.get("rules").isEmpty()) warnings.add("No rules are configured; accepted messages will be retained for review");
            for (JsonNode source : d.path("sources")) if ("FIRST_MATCHING".equals(source.path("ruleSelection").asText())) warnings.add("Source '" + source.path("id").asText() + "' uses FIRST_MATCHING; rule order affects delivery");
            if (d.path("rules").size() > 0) warnings.add("Delivery is at-least-once; retries and reprocessing can create duplicates at recipients");
        }
        return new ValidationResponse(errors, warnings);
    }
    private static Set<String> identifiers(JsonNode array, String type, List<String> errors) { Set<String> result=new HashSet<>(); for (JsonNode item:array) { String id=item.path("id").asText(); if (id.isBlank()) errors.add("$."+type+": id is required"); else if(!result.add(id)) errors.add("$."+type+": duplicate id '"+id+"'"); } return result; }
    public record PublishRequest(JsonNode document, Integer expectedVersion) {}
    public record ValidationResponse(List<String> errors, List<String> warnings) {}
}
