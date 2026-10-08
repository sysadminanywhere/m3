package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;

/** Typed operations shared by the visual editor, preview and worker. No executable scripts. */
public final class RuleTransformations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "m3-transform:";
    public enum Operation {
        EXTRACT_FIELD("Extract JSON field"), TRIM("Trim whitespace"), UPPERCASE("Uppercase"),
        LOWERCASE("Lowercase"), REPLACE("Replace text"), ADD_PREFIX("Add prefix"),
        ADD_SUFFIX("Add suffix"), JSON_TEXT("Serialize as JSON text");
        private final String label;
        Operation(String label) { this.label = label; }
        public String label() { return label; }
    }
    public record Spec(Operation operation, String field, String value, String replacement) { }
    private RuleTransformations() { }

    public static Spec parse(String stored) {
        if (stored != null && stored.matches("\\$\\.[A-Za-z_][A-Za-z0-9_-]*"))
            return new Spec(Operation.EXTRACT_FIELD, stored.substring(2), "", "");
        if (stored == null || !stored.startsWith(PREFIX))
            throw new IllegalArgumentException("Select a supported transformation in the visual editor");
        try {
            var spec = JSON.readValue(stored.substring(PREFIX.length()), Spec.class);
            validate(spec); return spec;
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid transformation configuration"); }
    }
    public static String encode(Spec spec) {
        validate(spec);
        if (spec.operation() == Operation.EXTRACT_FIELD) return "$." + spec.field();
        try { return PREFIX + JSON.writeValueAsString(spec); }
        catch (Exception invalid) { throw new IllegalArgumentException("Could not save transformation"); }
    }
    private static void validate(Spec spec) {
        if (spec == null || spec.operation() == null) throw new IllegalArgumentException("Select a transformation operation");
        if (spec.operation() == Operation.EXTRACT_FIELD && (spec.field() == null || !spec.field().matches("[A-Za-z_][A-Za-z0-9_-]*")))
            throw new IllegalArgumentException("Enter a top-level JSON field name");
        if ((spec.operation() == Operation.REPLACE || spec.operation() == Operation.ADD_PREFIX || spec.operation() == Operation.ADD_SUFFIX)
                && (spec.value() == null || spec.value().isEmpty() || spec.value().length() > 1000))
            throw new IllegalArgumentException("Text parameter must contain 1–1000 characters");
        if (spec.operation() == Operation.REPLACE && (spec.replacement() == null || spec.replacement().length() > 1000))
            throw new IllegalArgumentException("Replacement must contain at most 1000 characters");
    }
    public static Object apply(Object input, String stored) {
        var spec = parse(stored);
        if (spec.operation() == Operation.EXTRACT_FIELD) {
            if (!(input instanceof Map<?,?> fields)) throw new IllegalArgumentException("Field extraction requires a JSON object");
            if (!fields.containsKey(spec.field()) || fields.get(spec.field()) == null)
                throw new IllegalArgumentException("Transformation field is missing: " + spec.field());
            return fields.get(spec.field());
        }
        if (spec.operation() == Operation.JSON_TEXT) {
            try { return JSON.writeValueAsString(input); }
            catch (Exception invalid) { throw new IllegalArgumentException("Payload cannot be serialized as JSON"); }
        }
        if (!(input instanceof String text)) throw new IllegalArgumentException("This operation requires text; extract a JSON string field first");
        return switch (spec.operation()) {
            case TRIM -> text.strip();
            case UPPERCASE -> text.toUpperCase(Locale.ROOT);
            case LOWERCASE -> text.toLowerCase(Locale.ROOT);
            case REPLACE -> text.replace(spec.value(), spec.replacement());
            case ADD_PREFIX -> spec.value() + text;
            case ADD_SUFFIX -> text + spec.value();
            default -> throw new IllegalArgumentException("Unsupported transformation");
        };
    }
    public static String describe(String stored) {
        if (stored == null) return "Not configured";
        try {
            var spec = parse(stored);
            return switch (spec.operation()) {
                case EXTRACT_FIELD -> "Extract JSON field: " + spec.field();
                case REPLACE -> "Replace “" + spec.value() + "” with “" + spec.replacement() + "”";
                case ADD_PREFIX, ADD_SUFFIX -> spec.operation().label() + ": " + spec.value();
                default -> spec.operation().label();
            };
        } catch (IllegalArgumentException invalid) { return "Invalid transformation"; }
    }
}
