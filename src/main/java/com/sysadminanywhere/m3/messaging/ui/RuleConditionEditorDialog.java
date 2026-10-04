package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;

final class RuleConditionEditorDialog extends Dialog {
    static String operatorLabel(ConditionOperator operator) {
        return switch (operator) {
            case EQUALS -> "equals"; case NOT_EQUALS -> "does not equal"; case CONTAINS -> "contains";
            case REGEX -> "matches regex"; case GREATER -> "is greater than"; case LESS -> "is less than";
        };
    }
    RuleConditionEditorDialog(RuleService rules, long ruleId, RuleCondition existing, Runnable onSave) {
        setHeaderTitle(existing == null ? "Add condition" : "Edit condition");
        setWidth("520px"); setMaxWidth("calc(100vw - 32px)");
        var source = new ComboBox<String>("Compare value from");
        source.setItems("Metadata", "JSON field", "Whole body"); source.setValue("Metadata");
        var name = new ComboBox<String>("Field name");
        name.setItems("sourceSystem", "contentType", "charset", "fileName", "correlationId");
        name.setAllowCustomValue(true); name.addCustomValueSetListener(e -> name.setValue(e.getDetail()));
        name.setHelperText("Select or enter a top-level field name.");
        var operator = new ComboBox<ConditionOperator>("Comparison");
        operator.setItems(ConditionOperator.values()); operator.setItemLabelGenerator(RuleConditionEditorDialog::operatorLabel);
        operator.setValue(ConditionOperator.EQUALS);
        var value = new TextField("Compare with"); value.setMaxLength(1000); value.setValueChangeMode(ValueChangeMode.EAGER);
        var connector = new ComboBox<LogicalOperator>("Combine with previous conditions");
        connector.setItems(LogicalOperator.values()); connector.setValue(LogicalOperator.AND);
        connector.setHelperText("Creation order; AND takes precedence over OR. The first connector is ignored.");
        var sentence = new Span(); sentence.addClassName("rule-condition-summary");
        Runnable update = () -> {
            name.setVisible(!"Whole body".equals(source.getValue()));
            sentence.setText(source.getValue() + (name.isVisible() ? " “" + (name.getValue() == null ? "…" : name.getValue()) + "”" : "")
                    + " " + (operator.getValue() == null ? "…" : operatorLabel(operator.getValue())) + " “" + value.getValue() + "”");
        };
        source.addValueChangeListener(e -> update.run()); name.addValueChangeListener(e -> update.run());
        operator.addValueChangeListener(e -> update.run()); value.addValueChangeListener(e -> update.run());
        if (existing != null) {
            String stored = existing.getField();
            source.setValue(stored.equals("payload") ? "Whole body" : stored.startsWith("header.") ? "Metadata" : "JSON field");
            if (!stored.equals("payload")) name.setValue(stored.substring(stored.indexOf('.') + 1));
            operator.setValue(existing.getOperator()); value.setValue(existing.getValue()); connector.setValue(existing.getLogicalOperator());
        }
        var form = new VerticalLayout(source, name, operator, value, connector, sentence); form.setPadding(false);
        source.setWidthFull(); name.setWidthFull(); operator.setWidthFull(); value.setWidthFull(); connector.setWidthFull();
        add(form); update.run();
        var save = new Button("Save", e -> {
            try {
                if (source.getValue() == null || (!"Whole body".equals(source.getValue()) && name.getValue() == null))
                    throw new IllegalArgumentException("Select a value source and field");
                String field = "Whole body".equals(source.getValue()) ? "payload"
                        : ("Metadata".equals(source.getValue()) ? "header." : "payload.") + name.getValue();
                if (existing == null) rules.addCondition(ruleId, field, operator.getValue(), value.getValue(), connector.getValue());
                else rules.updateCondition(ruleId, existing.getId(), field, operator.getValue(), value.getValue(), connector.getValue());
                onSave.run(); close();
            } catch (IllegalArgumentException invalid) {
                Notification.show(invalid.getMessage(), 4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY); getFooter().add(new Button("Cancel", e -> close()), save);
    }
}
