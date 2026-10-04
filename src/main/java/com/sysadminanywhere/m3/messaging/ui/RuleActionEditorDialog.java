package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

final class RuleActionEditorDialog extends Dialog {
    static String description(RuleAction action) {
        return switch (action.getActionType()) {
            case TRANSFORM -> action.getTransformationScript() == null ? "Not configured" : action.getTransformationScript();
            case ENRICH -> action.getMetadataKey() + " = " + action.getMetadataValue();
            case FILTER -> Boolean.TRUE.equals(action.getFilterResult()) ? "Pass message" : "Discard message";
            case ROUTE -> action.getTargetChannel() == null ? "Not configured" : action.getTargetChannel();
        };
    }
    RuleActionEditorDialog(RuleService rules, long ruleId, Runnable onSave) {
        setHeaderTitle("Add Action"); setWidth("480px"); setMaxWidth("calc(100vw - 32px)");
        var type = new ComboBox<ActionType>("Action Type");
        type.setItems(ActionType.TRANSFORM, ActionType.FILTER, ActionType.ENRICH); type.setRequired(true);
        var script = new TextField("Transformation Script"); script.setMaxLength(1000);
        script.setHelperText("Extract one top-level JSON field, for example $.body");
        var filter = new Checkbox("Pass message"); filter.setValue(true);
        var key = new TextField("Metadata Key"); key.setMaxLength(RuleAction.METADATA_KEY_MAX_LENGTH);
        var value = new TextField("Metadata Value"); value.setMaxLength(RuleAction.METADATA_VALUE_MAX_LENGTH);
        script.setVisible(false); filter.setVisible(false); key.setVisible(false); value.setVisible(false);
        type.addValueChangeListener(event -> {
            script.setVisible(event.getValue() == ActionType.TRANSFORM);
            filter.setVisible(event.getValue() == ActionType.FILTER);
            key.setVisible(event.getValue() == ActionType.ENRICH); value.setVisible(event.getValue() == ActionType.ENRICH);
        });
        var form = new VerticalLayout(type, script, filter, key, value); form.setPadding(false);
        type.setWidthFull(); script.setWidthFull(); key.setWidthFull(); value.setWidthFull(); add(form);
        var save = new Button("Add", event -> {
            try {
                rules.createAction(ruleId, type.getValue(), script.getValue(), filter.getValue(), key.getValue(), value.getValue());
                onSave.run(); close();
            } catch (IllegalArgumentException error) {
                Notification.show(error.getMessage(), 4000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(new Button("Cancel", event -> close()), save);
    }
}
