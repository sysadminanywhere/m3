package com.sysadminanywhere.m3.messaging.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.sysadminanywhere.m3.messaging.service.RuleTransformations;
import com.sysadminanywhere.m3.messaging.service.RuleTransformations.Operation;
import com.sysadminanywhere.m3.messaging.service.RuleTransformations.Spec;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.*;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.Comparator;
import java.util.List;

final class RuleActionEditorDialog extends Dialog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ComboBox<ActionType> type = new ComboBox<>("Action");
    private final ComboBox<Operation> operation = new ComboBox<>("Transformation");
    private final TextField field = new TextField("JSON field");
    private final TextField parameter = new TextField("Text");
    private final TextField replacement = new TextField("Replace with");
    private final ComboBox<Boolean> filter = new ComboBox<>("Message outcome");
    private final ComboBox<String> key = new ComboBox<>("Metadata key");
    private final TextField value = new TextField("Metadata value");
    private final ComboBox<String> format = new ComboBox<>("Example format");
    private final TextArea sample = new TextArea("Input example");
    private final TextArea result = new TextArea("Result");
    private final Span error = new Span();
    private final VerticalLayout preview = new VerticalLayout(format, sample, result, error);

    static List<RuleAction> orderedActions(Rule rule) {
        return rule.getActions().stream().filter(a -> a.getActionType() != ActionType.ROUTE)
                .sorted(Comparator.comparing(RuleAction::getPriority)
                        .thenComparing(RuleAction::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
    static String description(RuleAction action) {
        return switch (action.getActionType()) {
            case TRANSFORM -> RuleTransformations.describe(action.getTransformationScript());
            case ENRICH -> action.getMetadataKey() + " = " + action.getMetadataValue();
            case FILTER -> Boolean.TRUE.equals(action.getFilterResult()) ? "Pass message" : "Discard message";
            case ROUTE -> action.getTargetChannel() == null ? "Not configured" : action.getTargetChannel();
        };
    }
    RuleActionEditorDialog(RuleService rules, long ruleId, Runnable onSave) { this(rules, ruleId, null, onSave); }
    RuleActionEditorDialog(RuleService rules, long ruleId, RuleAction existing, Runnable onSave) {
        setHeaderTitle(existing == null ? "Add action" : "Edit action");
        setWidth("640px"); setMaxWidth("calc(100vw - 32px)");
        type.setItems(ActionType.TRANSFORM, ActionType.ENRICH, ActionType.FILTER);
        type.setItemLabelGenerator(t -> switch(t) {
            case TRANSFORM -> "Transform body"; case ENRICH -> "Add metadata";
            case FILTER -> "Filter message"; default -> "Route";
        });
        operation.setItems(Operation.values()); operation.setItemLabelGenerator(Operation::label);
        field.setMaxLength(100); field.setHelperText("Top-level field name, for example body");
        parameter.setMaxLength(1000); replacement.setMaxLength(1000);
        filter.setItems(true, false); filter.setItemLabelGenerator(pass -> pass ? "Pass message" : "Discard message");
        filter.setHelperText("Applied when rule conditions match, before transformations. The first filter in execution order is used.");
        key.setItems("outputCharset", "traceId", "approved", "category"); key.setAllowCustomValue(true);
        key.addCustomValueSetListener(e -> key.setValue(e.getDetail()));
        key.setHelperText("Select or enter a key. outputCharset controls encoding of the transformed body.");
        value.setMaxLength(RuleAction.METADATA_VALUE_MAX_LENGTH);
        var order = new IntegerField("Execution order"); order.setMin(0); order.setMax(9999);
        order.setHelperText("Lower numbers execute first; ties follow creation order.");
        order.setValue(existing == null ? Math.min(9999, orderedActions(rules.findById(ruleId)).stream()
                .mapToInt(RuleAction::getPriority).max().orElse(-1) + 1) : existing.getPriority());
        format.setItems("Text", "JSON"); format.setValue("JSON");
        sample.setMaxLength(10000); sample.setValue("{\"body\":\"  Hello M3  \"}");
        sample.setMinHeight("100px"); result.setMinHeight("100px"); result.setReadOnly(true);
        error.getStyle().set("color", "var(--lumo-error-text-color)");
        preview.setPadding(false); preview.addClassName("rule-preview");
        preview.addComponentAsFirst(new Span("Try this operation on an example. Nothing is saved or sent."));
        var form = new VerticalLayout(type, operation, field, parameter, replacement, filter, key, value, order, preview);
        form.setPadding(false);
        for (var component : List.<com.vaadin.flow.component.HasSize>of(type, operation, field, parameter, replacement, filter, key, value, order, format, sample, result))
            component.setWidthFull();
        add(form);
        type.addValueChangeListener(e -> updatePreview());
        operation.addValueChangeListener(e -> updatePreview());
        format.addValueChangeListener(e -> updatePreview());
        for (var input : List.of(field, parameter, replacement)) {
            input.setValueChangeMode(ValueChangeMode.EAGER); input.addValueChangeListener(e -> updatePreview());
        }
        sample.setValueChangeMode(ValueChangeMode.EAGER); sample.addValueChangeListener(e -> updatePreview());
        operation.setValue(Operation.EXTRACT_FIELD); field.setValue("body"); filter.setValue(true);
        if (existing != null) {
            if (existing.getActionType() == ActionType.TRANSFORM) {
                try {
                    var spec = RuleTransformations.parse(existing.getTransformationScript());
                    operation.setValue(spec.operation()); field.setValue(spec.field() == null ? "" : spec.field());
                    parameter.setValue(spec.value() == null ? "" : spec.value());
                    replacement.setValue(spec.replacement() == null ? "" : spec.replacement());
                } catch (IllegalArgumentException invalid) { operation.clear(); }
            }
            if (existing.getMetadataKey() != null) key.setValue(existing.getMetadataKey());
            if (existing.getMetadataValue() != null) value.setValue(existing.getMetadataValue());
            if (existing.getFilterResult() != null) filter.setValue(existing.getFilterResult());
        }
        type.setValue(existing == null ? ActionType.TRANSFORM : existing.getActionType());
        var save = new Button("Save", e -> {
            try {
                rules.saveVisualAction(ruleId, existing == null ? null : existing.getId(), type.getValue(),
                        type.getValue() == ActionType.TRANSFORM ? RuleTransformations.encode(spec()) : null,
                        filter.getValue(), key.getValue(), value.getValue(), order.getValue());
                onSave.run(); close();
            } catch (IllegalArgumentException invalid) {
                Notification.show(invalid.getMessage(), 4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(new Button("Cancel", e -> close()), save);
        updatePreview();
    }
    private Spec spec() { return new Spec(operation.getValue(), field.getValue(), parameter.getValue(), replacement.getValue()); }
    private void updatePreview() {
        boolean transform = type.getValue() == ActionType.TRANSFORM;
        var op = operation.getValue();
        operation.setVisible(transform); preview.setVisible(transform);
        field.setVisible(transform && op == Operation.EXTRACT_FIELD);
        parameter.setVisible(transform && (op == Operation.REPLACE || op == Operation.ADD_PREFIX || op == Operation.ADD_SUFFIX));
        parameter.setLabel(op == Operation.REPLACE ? "Find text" : "Text to add");
        replacement.setVisible(transform && op == Operation.REPLACE);
        filter.setVisible(type.getValue() == ActionType.FILTER);
        key.setVisible(type.getValue() == ActionType.ENRICH); value.setVisible(key.isVisible());
        if (!transform) return;
        try {
            Object input = "JSON".equals(format.getValue()) ? JSON.readValue(sample.getValue(), Object.class) : sample.getValue();
            Object output = RuleTransformations.apply(input, RuleTransformations.encode(spec()));
            result.setValue(output instanceof String text ? text : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            error.setText("");
        } catch (Exception invalid) { result.clear(); error.setText(invalid.getMessage() == null ? "Invalid example" : invalid.getMessage()); }
    }
}
