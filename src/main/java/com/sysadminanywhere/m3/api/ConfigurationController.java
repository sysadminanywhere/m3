package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private final ObjectMapper mapper;
    public ConfigurationController(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @GetMapping("/schema")
    public Map<String, Object> schema() { return Map.of("formatVersion", "2", "service", "m3-community", "status", "supported"); }
    @GetMapping("/version")
    public Map<String, Integer> version() { return Map.of("version", jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class)); }

    @GetMapping("/current")
    public Map<String, Object> current() {
        var rows = jdbc.query("select version,document_json::text from community.config_bundle order by version desc limit 1", (rs, index) -> new StoredConfiguration(rs.getInt(1), rs.getString(2)));
        if (rows.isEmpty()) return Map.of("version", 0, "document", mapper.createObjectNode().put("formatVersion", "2"));
        var current = rows.get(0);
        try { return Map.of("version", current.version(), "document", mapper.readTree(current.document())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Stored configuration is invalid", error); }
    }
    private record StoredConfiguration(int version, String document) {}

    @PostMapping("/validate")
    public ValidationResponse validate(@RequestBody JsonNode document) {
        if (document == null || !document.isObject() || !document.has("formatVersion") || !"2".equals(document.path("formatVersion").asText())) return validateDocument(document);
        try {
            Integer version = jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class);
            JsonNode previous = version == null || version == 0 ? mapper.createObjectNode() : mapper.readTree(jdbc.queryForObject("select document_json::text from community.config_bundle where version=?", String.class, version));
            JsonNode merged = merge(previous, document);
            return withGroupChangeWarnings(validateDocument(merged), previous, merged);
        } catch (IllegalArgumentException error) {
            return new ValidationResponse(List.of(error.getMessage()), List.of());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Stored configuration is invalid", error);
        }
    }

    @PostMapping("/publish")
    @Transactional
    public ResponseEntity<?> publish(@RequestBody PublishRequest request) {
        if (request.expectedVersion() == null || request.expectedVersion() < 0 || request.document() == null || !request.document().isObject() || !"2".equals(request.document().path("formatVersion").asText()))
            return ResponseEntity.badRequest().body(Map.of("error", "expectedVersion and document are required"));
        jdbc.execute("select pg_advisory_xact_lock(8273641)");
        Integer current = jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class);
        int expected = request.expectedVersion();
        if (expected != current) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "configuration_version_conflict", "currentVersion", current));
        JsonNode merged;
        JsonNode previous;
        try {
            previous = current == 0 ? mapper.createObjectNode() : mapper.readTree(jdbc.queryForObject("select document_json::text from community.config_bundle where version=?", String.class, current));
            merged = merge(previous, request.document());
        } catch (Exception error) { return ResponseEntity.badRequest().body(Map.of("error", "invalid_configuration", "message", error.getMessage())); }
        ValidationResponse validation = withGroupChangeWarnings(validateDocument(merged), previous, merged);
        if (!validation.errors().isEmpty()) return ResponseEntity.badRequest().body(validation);
        int next = current + 1;
        jdbc.update("insert into community.config_bundle(version, document_json) values (?, cast(? as jsonb))", next, merged.toString());
        return ResponseEntity.ok(Map.of("version", next, "status", "published", "warnings", validation.warnings()));
    }

    private static ValidationResponse withGroupChangeWarnings(ValidationResponse validation, JsonNode previous, JsonNode updated) {
        List<String> warnings = new ArrayList<>(validation.warnings());
        for (JsonNode rule : updated.path("rules")) {
            String id = rule.path("id").asText();
            for (JsonNode old : previous.path("rules")) {
                if (!id.equals(old.path("id").asText())) continue;
                String oldGroup = old.path("executionGroup").asText("default"), newGroup = rule.path("executionGroup").asText("default");
                if (!oldGroup.equals(newGroup)) warnings.add("Rule '" + id + "' moves from group '" + oldGroup + "' to '" + newGroup + "'; existing jobs remain in their original group");
                break;
            }
        }
        return new ValidationResponse(validation.errors(), warnings);
    }

    static JsonNode merge(JsonNode previous, JsonNode update) {
        ObjectNode result = previous.isObject() ? ((ObjectNode) previous).deepCopy() : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        update.fields().forEachRemaining(field -> { if (!List.of("connections", "sources", "recipients", "rules", "delete").contains(field.getKey())) result.set(field.getKey(), field.getValue().deepCopy()); });
        JsonNode deletes = update.path("delete");
        if (!deletes.isMissingNode() && !deletes.isObject()) throw new IllegalArgumentException("$.delete must be an object");
        deletes.fieldNames().forEachRemaining(key -> { if (!List.of("connections", "sources", "recipients", "rules").contains(key)) throw new IllegalArgumentException("Unknown deletion type: " + key); });
        for (String kind : List.of("connections", "sources", "recipients", "rules")) {
            JsonNode incoming = update.path(kind), removed = deletes.path(kind);
            if (!incoming.isMissingNode() && !incoming.isArray()) throw new IllegalArgumentException("$." + kind + " must be an array");
            if (!removed.isMissingNode() && !removed.isArray()) throw new IllegalArgumentException("$.delete." + kind + " must be an array");
            java.util.LinkedHashMap<String, JsonNode> objects = new java.util.LinkedHashMap<>();
            for (JsonNode item : previous.path(kind)) objects.put(item.path("id").asText(), item);
            for (JsonNode id : removed) { if (!id.isTextual() || !objects.containsKey(id.asText())) throw new IllegalArgumentException("Unknown " + kind + " deletion: " + id); objects.remove(id.asText()); }
            Set<String> seen = new HashSet<>();
            for (JsonNode item : incoming) { String id = item.path("id").asText(); if (id.isBlank() || !seen.add(id)) throw new IllegalArgumentException("Missing or duplicate id in " + kind); for (JsonNode deleted : removed) if (id.equals(deleted.asText())) throw new IllegalArgumentException("Cannot update and delete " + id); objects.put(id, item); }
            ArrayNode array = result.putArray(kind); objects.values().forEach(array::add);
        }
        return result;
    }

    private ValidationResponse validateDocument(JsonNode d) {
        List<String> errors = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        if (d == null || !d.isObject()) errors.add("$: document must be an object");
        else {
            rejectInlineCredentials(d, "$", errors);
            if (!d.has("formatVersion")) errors.add("$.formatVersion: required");
            else if (!"2".equals(d.get("formatVersion").asText())) errors.add("$.formatVersion: must be '2'");
            for (String key : List.of("connections", "sources", "recipients", "rules")) if (d.has(key) && !d.get(key).isArray()) errors.add("$." + key + ": must be an array");
            Set<String> connections = identifiers(d.path("connections"), "connections", errors);
            Set<String> sources = identifiers(d.path("sources"), "sources", errors);
            Set<String> recipients = identifiers(d.path("recipients"), "recipients", errors);
            identifiers(d.path("rules"), "rules", errors);
            for (String kind : List.of("connections", "recipients")) {
                JsonNode items = d.path(kind);
                for (int i = 0; i < items.size(); i++) {
                    JsonNode item = items.get(i);
                    if (!"directory".equals(item.path("type").asText())) errors.add("$." + kind + "[" + i + "].type: only directory is supported");
                    if (!item.path("settings").path("path").isTextual() || item.path("settings").path("path").asText().isBlank()) errors.add("$." + kind + "[" + i + "].settings.path: nonempty path required");
                }
            }
            for (int i = 0; i < d.path("sources").size(); i++) {
                String connectionId = d.path("sources").get(i).path("connectionId").asText();
                String selection = d.path("sources").get(i).path("ruleSelection").asText("ALL_MATCHING");
                if (!List.of("ALL_MATCHING", "FIRST_MATCHING").contains(selection)) errors.add("$.sources[" + i + "].ruleSelection: invalid selection");
                if (connectionId.isBlank()) errors.add("$.sources[" + i + "].connectionId: required");
                else if (!connections.contains(connectionId)) errors.add("$.sources[" + i + "].connectionId: unknown connection '" + connectionId + "'");
            }
            for (int i = 0; i < d.path("rules").size(); i++) {
                JsonNode rule = d.path("rules").get(i);
                String mode = rule.path("processingMode").asText("PARALLEL");
                if (!List.of("PARALLEL", "SEQUENTIAL").contains(mode)) errors.add("$.rules[" + i + "].processingMode: must be PARALLEL or SEQUENTIAL");
                String onError = rule.path("onError").asText("STOP");
                if (!List.of("STOP", "CONTINUE").contains(onError)) errors.add("$.rules[" + i + "].onError: must be STOP or CONTINUE");
                if (!rule.path("sourceIds").isArray() || rule.path("sourceIds").isEmpty()) errors.add("$.rules[" + i + "].sourceIds: nonempty array required");
                if (rule.has("enabled") && !rule.path("enabled").isBoolean()) errors.add("$.rules[" + i + "].enabled: must be a boolean");
                if (rule.has("autoRetryUncertain") && !rule.path("autoRetryUncertain").isBoolean()) errors.add("$.rules[" + i + "].autoRetryUncertain: must be a boolean");
                if (rule.path("autoRetryUncertain").isBoolean() && rule.path("autoRetryUncertain").asBoolean()) warnings.add("Rule '" + rule.path("id").asText() + "' allows automatic retry of uncertain deliveries when enabled on the worker; duplicate delivery is possible");
                if (rule.has("executionGroup") && (!rule.path("executionGroup").isTextual() || rule.path("executionGroup").asText().isBlank())) errors.add("$.rules[" + i + "].executionGroup: nonempty string required");
                if (rule.has("condition") && (!rule.path("condition").isObject() || rule.path("condition").fieldNames().hasNext() && (!rule.path("condition").has("fileNameEquals") || rule.path("condition").size() != 1 || !rule.path("condition").path("fileNameEquals").isTextual()))) errors.add("$.rules[" + i + "].condition: only fileNameEquals is supported");
                else if (rule.path("condition").has("fileNameEquals") && rule.path("condition").path("fileNameEquals").asText().isBlank()) errors.add("$.rules[" + i + "].condition.fileNameEquals: nonempty filename required");
                for (JsonNode source : rule.path("sourceIds")) if (!sources.contains(source.asText())) errors.add("$.rules[" + i + "].sourceIds: unknown source '" + source.asText() + "'");
                String recipient = rule.path("recipientId").asText();
                if (recipient.isBlank()) errors.add("$.rules[" + i + "].recipientId: required");
                else if (!recipients.contains(recipient)) errors.add("$.rules[" + i + "].recipientId: unknown recipient '" + recipient + "'");
            }
            if (!d.has("rules") || d.get("rules").isEmpty()) warnings.add("No rules are configured; accepted messages will be retained for review");
            for (JsonNode source : d.path("sources")) if ("FIRST_MATCHING".equals(source.path("ruleSelection").asText())) warnings.add("Source '" + source.path("id").asText() + "' uses FIRST_MATCHING; rule order affects delivery");
            if (d.path("rules").size() > 0) warnings.add("Delivery is at-least-once; retries and reprocessing can create duplicates at recipients");
        }
        return new ValidationResponse(errors, warnings);
    }
    private static void rejectInlineCredentials(JsonNode node, String path, List<String> errors) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            String fieldPath = path + "." + field.getKey();
            String key = field.getKey().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
            if (Set.of("password", "secret", "token", "privatekey", "credential", "apikey", "clientsecret", "accesstoken", "secretaccesskey").contains(key)) errors.add(fieldPath + ": inline credentials are forbidden; use a secret reference");
            else rejectInlineCredentials(field.getValue(), fieldPath, errors);
        });
        else if (node.isArray()) for (int i=0; i<node.size(); i++) rejectInlineCredentials(node.get(i), path + "[" + i + "]", errors);
    }
    private static Set<String> identifiers(JsonNode array, String type, List<String> errors) { Set<String> result=new HashSet<>(); for (JsonNode item:array) { String id=item.path("id").asText(); if (id.isBlank()) errors.add("$."+type+": id is required"); else if(!result.add(id)) errors.add("$."+type+": duplicate id '"+id+"'"); } return result; }
    public record PublishRequest(JsonNode document, Integer expectedVersion) {}
    public record ValidationResponse(List<String> errors, List<String> warnings) {}
}
