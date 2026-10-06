package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

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
    private final ComboBox<ActionType> type = new ComboBox<>(t("Action"));
    private final ComboBox<Operation> operation = new ComboBox<>(t("Transformation"));
    private final TextField field = new TextField(t("JSON field"));
    private final TextField parameter = new TextField(t("Text"));
    private final TextField replacement = new TextField(t("Replace with"));
    private final ComboBox<Boolean> filter = new ComboBox<>(t("Message outcome"));
    private final ComboBox<String> key = new ComboBox<>(t("Metadata key"));
    private final TextField value = new TextField(t("Metadata value"));
    private final ComboBox<String> format = new ComboBox<>(t("Example format"));
    private final TextArea sample = new TextArea(t("Input example"));
    private final TextArea result = new TextArea(t("Result"));
    private final Span error = new Span();
    private final VerticalLayout preview = new VerticalLayout(format, sample, result, error);

    static List<RuleAction> orderedActions(Rule rule) {
        return rule.getActions().stream().filter(a -> a.getActionType() != ActionType.ROUTE)
                .sorted(Comparator.comparing(RuleAction::getPriority)
                        .thenComparing(RuleAction::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
    static String description(RuleAction action) {
        return switch (action.getActionType()) {
            case TRANSFORM -> transformationDescription(action.getTransformationScript());
            case ENRICH -> action.getMetadataKey() + " = " + action.getMetadataValue();
            case FILTER -> Boolean.TRUE.equals(action.getFilterResult()) ? t("Pass message") : t("Discard message");
            case ROUTE -> action.getTargetChannel() == null ? t("Not configured") : action.getTargetChannel();
        };
    }
    static String transformationDescription(String stored) {
        try {
            var spec = RuleTransformations.parse(stored);
            return switch (spec.operation()) {
                case EXTRACT_FIELD -> t("Extract JSON field") + ": " + spec.field();
                case REPLACE -> t("Replace {0} with {1}", spec.value(), spec.replacement());
                case ADD_PREFIX, ADD_SUFFIX -> t(spec.operation().label()) + ": " + spec.value();
                default -> t(spec.operation().label());
            };
        } catch (IllegalArgumentException invalid) { return t("Not configured"); }
    }
    RuleActionEditorDialog(RuleService rules, long ruleId, Runnable onSave) { this(rules, ruleId, null, onSave); }
    RuleActionEditorDialog(RuleService rules, long ruleId, RuleAction existing, Runnable onSave) { this(rules, ruleId, existing, null, onSave); }
    RuleActionEditorDialog(RuleService rules, long ruleId, RuleAction existing, ActionType initialType, Runnable onSave) {
        setHeaderTitle(existing == null ? t("Add action") : t("Edit action"));
        setWidth("640px"); setMaxWidth("calc(100vw - 32px)");
        type.setItems(ActionType.TRANSFORM, ActionType.ENRICH, ActionType.FILTER);
        type.setRequired(true);
        type.setItemLabelGenerator(t -> switch(t) {
            case TRANSFORM -> t("Transform body"); case ENRICH -> t("Add metadata");
            case FILTER -> t("Filter message"); default -> t("Route");
        });
        operation.setItems(Operation.values()); operation.setItemLabelGenerator(op -> t(op.label()));
        operation.setRequired(true);
        field.setRequired(true); parameter.setRequired(true);
        filter.setRequired(true); key.setRequired(true); value.setRequired(true);
        field.setMaxLength(100); field.setHelperText(t("Top-level field name, for example body"));
        parameter.setMaxLength(1000); replacement.setMaxLength(1000);
        filter.setItems(true, false); filter.setItemLabelGenerator(pass -> pass ? "Pass message" : "Discard message");
        filter.setHelperText(t("Applied when rule conditions match, before transformations. The first filter in execution order is used."));
        key.setItems("outputCharset", "traceId", "approved", "category"); key.setAllowCustomValue(true);
        key.addCustomValueSetListener(e -> key.setValue(e.getDetail()));
        key.setHelperText(t("Select or enter a key. outputCharset controls encoding of the transformed body."));
        value.setMaxLength(RuleAction.METADATA_VALUE_MAX_LENGTH);
        var order = new IntegerField(t("Execution order")); order.setMin(0); order.setMax(9999); order.setRequired(true);
        order.setHelperText(t("Lower numbers execute first; ties follow creation order."));
        order.setValue(existing == null ? Math.min(9999, orderedActions(rules.findById(ruleId)).stream()
                .mapToInt(RuleAction::getPriority).max().orElse(-1) + 1) : existing.getPriority());
        format.setItems("Text", "JSON"); format.setItemLabelGenerator(Translations::t); format.setValue("JSON");
        sample.setMaxLength(10000); sample.setValue("{\"body\":\"  Hello M3  \"}");
        sample.setMinHeight("100px"); result.setMinHeight("100px"); result.setReadOnly(true);
        error.getStyle().set("color", "var(--lumo-error-text-color)");
        preview.setPadding(false); preview.addClassName("rule-preview");
        preview.addComponentAsFirst(new Span(t("Try this operation on an example. Nothing is saved or sent.")));
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
        type.setValue(existing == null ? (initialType == null ? ActionType.TRANSFORM : initialType) : existing.getActionType());
        var guard = new com.sysadminanywhere.m3.base.ui.UnsavedChangesGuard(this, type, operation, field, parameter, replacement, filter, key, value, order);
        guard.markSaved();
        var save = new Button(t("Save"), e -> {
            try {
                rules.saveVisualAction(ruleId, existing == null ? null : existing.getId(), type.getValue(),
                        type.getValue() == ActionType.TRANSFORM ? RuleTransformations.encode(spec()) : null,
                        filter.getValue(), key.getValue(), value.getValue(), order.getValue());
                guard.markSaved(); onSave.run(); close();
            } catch (IllegalArgumentException invalid) {
                Notification.show(t(invalid.getMessage()), 4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(guard.indicator(), new Button(t("Cancel"), e -> guard.requestDiscard(this::close)), save);
        updatePreview();
    }
    private Spec spec() { return new Spec(operation.getValue(), field.getValue(), parameter.getValue(), replacement.getValue()); }
    private void updatePreview() {
        boolean transform = type.getValue() == ActionType.TRANSFORM;
        var op = operation.getValue();
        operation.setVisible(transform); preview.setVisible(transform);
        field.setVisible(transform && op == Operation.EXTRACT_FIELD);
        parameter.setVisible(transform && (op == Operation.REPLACE || op == Operation.ADD_PREFIX || op == Operation.ADD_SUFFIX));
        parameter.setLabel(op == Operation.REPLACE ? t("Find text") : t("Text to add"));
        replacement.setVisible(transform && op == Operation.REPLACE);
        filter.setVisible(type.getValue() == ActionType.FILTER);
        key.setVisible(type.getValue() == ActionType.ENRICH); value.setVisible(key.isVisible());
        operation.setRequiredIndicatorVisible(transform);
        field.setRequiredIndicatorVisible(transform && op == Operation.EXTRACT_FIELD);
        parameter.setRequiredIndicatorVisible(transform && (op == Operation.REPLACE || op == Operation.ADD_PREFIX || op == Operation.ADD_SUFFIX));
        replacement.setRequiredIndicatorVisible(false);
        filter.setRequiredIndicatorVisible(type.getValue() == ActionType.FILTER);
        key.setRequiredIndicatorVisible(type.getValue() == ActionType.ENRICH);
        value.setRequiredIndicatorVisible(type.getValue() == ActionType.ENRICH);
        if (!transform) return;
        try {
            Object input = "JSON".equals(format.getValue()) ? JSON.readValue(sample.getValue(), Object.class) : sample.getValue();
            Object output = RuleTransformations.apply(input, RuleTransformations.encode(spec()));
            result.setValue(output instanceof String text ? text : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            error.setText("");
        } catch (Exception invalid) { result.clear(); error.setText(invalid.getMessage() == null ? t("Invalid example") : t(invalid.getMessage())); }
    }
}
