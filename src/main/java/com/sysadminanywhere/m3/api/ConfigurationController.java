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
    @GetMapping("/versions") public List<VersionInfo> versions(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset) {
        if (limit<1 || limit>500 || offset<0) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_pagination");
        return jdbc.query("select version,published_at from community.config_bundle order by version desc limit ? offset ?",
                (rs,row) -> new VersionInfo(rs.getInt(1),rs.getObject(2,java.time.OffsetDateTime.class)),limit,offset);
    }
    @GetMapping("/versions/{version}") public ResponseEntity<?> versionDocument(@PathVariable int version) {
        if (version<1) return ResponseEntity.badRequest().build();
        var rows=jdbc.query("select document_json::text from community.config_bundle where version=?",(rs,row) -> rs.getString(1),version);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        try { return ResponseEntity.ok(Map.of("version",version,"document",mapper.readTree(rows.get(0)))); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Stored configuration is invalid",invalid); }
    }
    public record VersionInfo(int version,java.time.OffsetDateTime publishedAt) {}

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

    @PostMapping("/validate-replacement") public ValidationResponse validateReplacement(@RequestBody JsonNode document) {
        if (document==null || !document.isObject() || document.has("delete")) return new ValidationResponse(List.of("Full replacement document required; delete directives are not allowed"),List.of());
        ValidationResponse validation=validateDocument(document);
        var current=(JsonNode)current().get("document");
        return withGroupChangeWarnings(validation,current,document);
    }
    @Transactional @PostMapping("/publish-replacement") public ResponseEntity<?> publishReplacement(@RequestBody PublishRequest request) {
        if (request==null || request.expectedVersion()==null || request.expectedVersion()<0 || request.document()==null || !request.document().isObject()
                || request.document().has("delete") || !"2".equals(request.document().path("formatVersion").asText()))
            return ResponseEntity.badRequest().body(Map.of("error","full_replacement_and_expected_version_required"));
        jdbc.execute("select pg_advisory_xact_lock(8273641)");
        int current=jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle",Integer.class);
        if (request.expectedVersion()!=current) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","configuration_version_conflict","currentVersion",current));
        ValidationResponse validation=validateReplacement(request.document());
        if (!validation.errors().isEmpty()) return ResponseEntity.badRequest().body(validation);
        int next=current+1; jdbc.update("insert into community.config_bundle(version,document_json) values (?,cast(? as jsonb))",next,request.document().toString());
        return ResponseEntity.ok(Map.of("version",next,"status","published","warnings",validation.warnings()));
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
                    String type=item.path("type").asText(), base="$."+kind+"["+i+"]";
                    if (!List.of("directory","sftp").contains(type)) errors.add(base+".type: unsupported type");
                    JsonNode settings=item.path("settings");
                    if (!settings.path("path").isTextual() || settings.path("path").asText().isBlank()) errors.add(base+".settings.path: nonempty path required");
                    if ("sftp".equals(type)) {
                        String path=settings.path("path").asText();
                        if (!path.startsWith("/") || path.contains("\\") || path.contains("\u0000") || java.util.Arrays.asList(path.split("/",-1)).contains("..")) errors.add(base+".settings.path: absolute safe SFTP path required");
                        for (String field : List.of("host","username","credentialRef")) if (!settings.path(field).isTextual() || settings.path(field).asText().isBlank()) errors.add(base+".settings."+field+": nonempty value required");
                        if (!settings.path("hostKeyFingerprint").isTextual() || !settings.path("hostKeyFingerprint").asText().matches("SHA256:[A-Za-z0-9+/]{43}")) errors.add(base+".settings.hostKeyFingerprint: SHA256 fingerprint required");
                        if (settings.has("port") && (!settings.path("port").canConvertToInt() || !settings.path("port").isIntegralNumber() || settings.path("port").asInt()<1 || settings.path("port").asInt()>65535)) errors.add(base+".settings.port: integer from 1 to 65535 required");
                    }
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
            for (JsonNode connection : d.path("connections")) if ("sftp".equals(connection.path("type").asText()))
                for (JsonNode recipient : d.path("recipients")) if ("sftp".equals(recipient.path("type").asText())) {
                    JsonNode a=connection.path("settings"), b=recipient.path("settings");
                    if (a.path("host").asText().equalsIgnoreCase(b.path("host").asText()) && a.path("port").asInt(22)==b.path("port").asInt(22)
                            && a.path("username").asText().equals(b.path("username").asText()) && a.path("path").asText().replaceAll("/+$","").equals(b.path("path").asText().replaceAll("/+$","")))
                        warnings.add("SFTP source and recipient use the same directory; delivered files can be ingested again");
                }
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
