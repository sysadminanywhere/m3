package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

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
            case EQUALS -> t("equals"); case NOT_EQUALS -> t("does not equal"); case CONTAINS -> t("contains");
            case REGEX -> t("matches regex"); case GREATER -> t("is greater than"); case LESS -> t("is less than");
        };
    }
    RuleConditionEditorDialog(RuleService rules, long ruleId, RuleCondition existing, Runnable onSave) {
        setHeaderTitle(existing == null ? t("Add condition") : t("Edit condition"));
        setWidth("520px"); setMaxWidth("calc(100vw - 32px)");
        var source = new ComboBox<String>(t("Compare value from"));
        source.setRequired(true);
        source.setItems("Metadata", "JSON field", "Whole body"); source.setItemLabelGenerator(Translations::t); source.setValue("Metadata");
        var name = new ComboBox<String>(t("Field name"));
        name.setRequired(true);
        name.setItems("sourceSystem", "contentType", "charset", "fileName", "correlationId");
        name.setAllowCustomValue(true); name.addCustomValueSetListener(e -> name.setValue(e.getDetail()));
        name.setHelperText(t("Select or enter a top-level field name."));
        var operator = new ComboBox<ConditionOperator>(t("Comparison"));
        operator.setRequired(true);
        operator.setItems(ConditionOperator.values()); operator.setItemLabelGenerator(Translations::enumLabel); operator.setItemLabelGenerator(RuleConditionEditorDialog::operatorLabel);
        operator.setValue(ConditionOperator.EQUALS);
        var value = new TextField(t("Compare with")); value.setMaxLength(1000); value.setValueChangeMode(ValueChangeMode.EAGER);
        var connector = new ComboBox<LogicalOperator>(t("Combine with previous conditions"));
        connector.setRequired(true);
        connector.setItems(LogicalOperator.values()); connector.setItemLabelGenerator(Translations::enumLabel); connector.setValue(LogicalOperator.AND);
        connector.setHelperText(t("Creation order; AND takes precedence over OR. The first connector is ignored."));
        var sentence = new Span(); sentence.addClassName("rule-condition-summary");
        Runnable update = () -> {
            name.setVisible(!"Whole body".equals(source.getValue()));
            sentence.setText(t(source.getValue()) + (name.isVisible() ? " “" + (name.getValue() == null ? "…" : name.getValue()) + "”" : "")
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
        var guard = new com.sysadminanywhere.m3.base.ui.UnsavedChangesGuard(this, form);
        guard.markSaved();
        var save = new Button(t("Save"), e -> {
            try {
                if (source.getValue() == null || (!"Whole body".equals(source.getValue()) && name.getValue() == null))
                    throw new IllegalArgumentException(t("Select a value source and field"));
                String field = "Whole body".equals(source.getValue()) ? "payload"
                        : ("Metadata".equals(source.getValue()) ? "header." : "payload.") + name.getValue();
                if (existing == null) rules.addCondition(ruleId, field, operator.getValue(), value.getValue(), connector.getValue());
                else rules.updateCondition(ruleId, existing.getId(), field, operator.getValue(), value.getValue(), connector.getValue(), existing.getVersion());
                guard.markSaved(); onSave.run(); close();
            } catch (RuntimeException invalid) {
                Notification.show(com.sysadminanywhere.m3.base.ui.SaveErrors.message(invalid), 4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY); getFooter().add(guard.indicator(), new Button(t("Cancel"), e -> guard.requestDiscard(this::close)), save);
    }
}
