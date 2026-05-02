package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.util.function.Consumer;

public class RuleDialog extends Dialog {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;
    private final Consumer<Void> onSaveCallback;
    private Long ruleId;
    private Rule currentRule;

    final TextField nameField;
    final ComboBox<RuleType> typeField;
    final ComboBox<ChannelSettings> channelField;
    final NumberField priorityField;
    final Checkbox enabledField;
    final Grid<RuleCondition> conditionGrid;
    final Grid<RuleAction> actionGrid;

    public RuleDialog(RuleService ruleService, ChannelSettingsService channelSettingsService, Consumer<Void> onSaveCallback) {
        this(ruleService, channelSettingsService, null, onSaveCallback);
    }

    public RuleDialog(RuleService ruleService, ChannelSettingsService channelSettingsService, Rule rule, Consumer<Void> onSaveCallback) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;
        this.currentRule = rule;
        this.ruleId = rule != null ? rule.getId() : null;
        this.onSaveCallback = onSaveCallback;

        setHeaderTitle(rule == null ? "Add Rule" : "Edit Rule");
        setMinWidth("700px");
        setMaxWidth("900px");
        setHeight("90%");

        nameField = new TextField("Name");
        nameField.setRequired(true);
        nameField.setWidthFull();
        nameField.setMaxLength(100);
        nameField.setHelperText("Max 100 characters");
        nameField.setValueChangeMode(ValueChangeMode.EAGER);
        nameField.addValueChangeListener(event -> {
            if (event.getValue().length() > 100) {
                nameField.setInvalid(true);
                nameField.setErrorMessage("Name cannot exceed 100 characters");
            } else {
                nameField.setInvalid(false);
            }
        });

        typeField = new ComboBox<>("Type");
        typeField.setItems(RuleType.values());
        typeField.setRequired(true);
        typeField.setWidthFull();

        channelField = new ComboBox<>("Source Channel");
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.setRequired(true);
        channelField.setWidthFull();

        priorityField = new NumberField("Priority");
        priorityField.setValue(0.0);
        priorityField.setWidthFull();
        priorityField.setMin(0);
        priorityField.setMax(9999);
        priorityField.setStep(1);
        priorityField.setErrorMessage("Priority must be between 0 and 9999");

        enabledField = new Checkbox("Enabled");
        enabledField.setValue(true);

        var form = new HorizontalLayout(nameField, typeField, channelField, priorityField, enabledField);
        form.setWrap(true);
        form.setWidthFull();

        // Conditions section
        conditionGrid = new Grid<>();
        conditionGrid.addColumn(RuleCondition::getField).setHeader("Field");
        conditionGrid.addColumn(RuleCondition::getOperator).setHeader("Operator");
        conditionGrid.addColumn(RuleCondition::getValue).setHeader("Value");
        conditionGrid.addComponentColumn(condition -> {
            var deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            deleteButton.addClickListener(event -> deleteCondition(condition));
            return deleteButton;
        }).setHeader("Actions");
        conditionGrid.setEmptyStateText("No conditions");
        conditionGrid.setHeight("150px");

        var addConditionButton = new Button("Add Condition", event -> addCondition());
        addConditionButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

        var conditionSection = new VerticalLayout(
                new com.vaadin.flow.component.html.Span("Conditions"),
                conditionGrid,
                addConditionButton
        );
        conditionSection.setSpacing(true);
        conditionSection.setPadding(false);

        // Actions section
        actionGrid = new Grid<>();
        actionGrid.addColumn(RuleAction::getActionType).setHeader("Type");
        actionGrid.addColumn(RuleAction::getTargetChannel).setHeader("Target Channel");
        actionGrid.addComponentColumn(action -> {
            var deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            deleteButton.addClickListener(event -> deleteAction(action));
            return deleteButton;
        }).setHeader("Actions");
        actionGrid.setEmptyStateText("No actions");
        actionGrid.setHeight("150px");

        var addActionButton = new Button("Add Action", event -> addAction());
        addActionButton.addThemeVariants(ButtonVariant.LUMO_SMALL);

        var actionSection = new VerticalLayout(
                new com.vaadin.flow.component.html.Span("Actions"),
                actionGrid,
                addActionButton
        );
        actionSection.setSpacing(true);
        actionSection.setPadding(false);

        var content = new VerticalLayout(form, conditionSection, actionSection);
        content.setSpacing(true);
        content.setPadding(false);
        content.setWidthFull();
        content.setHeightFull();

        add(content);

        // Footer buttons
        var saveButton = new Button("Save", event -> saveRule());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> close());

        getFooter().add(cancelButton, saveButton);

        // Load data if editing
        if (rule != null) {
            loadRule(rule);
        }
    }

    private void loadRule(Rule rule) {
        nameField.setValue(rule.getName());
        typeField.setValue(rule.getRuleType());
        channelField.setItems(channelSettingsService.findAll());
        channelField.setValue(rule.getSourceChannel());
        priorityField.setValue((double) rule.getPriority());
        enabledField.setValue(rule.getEnabled());
        conditionGrid.setItems(rule.getConditions());
        actionGrid.setItems(rule.getActions());
    }

    private void saveRule() {
        // Validate name
        if (nameField.getValue().isBlank()) {
            Notification.show("Name is required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            nameField.focus();
            return;
        }

        if (nameField.getValue().length() > 100) {
            Notification.show("Name cannot exceed 100 characters", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            nameField.focus();
            return;
        }

        // Validate type
        if (typeField.getValue() == null) {
            Notification.show("Type is required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            typeField.focus();
            return;
        }

        // Validate source channel
        if (channelField.getValue() == null) {
            Notification.show("Source Channel is required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            channelField.focus();
            return;
        }

        // Validate priority
        var priorityValue = priorityField.getValue();
        if (priorityValue == null || priorityValue < 0 || priorityValue > 9999) {
            Notification.show("Priority must be between 0 and 9999", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            priorityField.focus();
            return;
        }

        // Validate that channels exist
        if (channelSettingsService.findAll().isEmpty()) {
            Notification.show("No channels available. Please create a channel first.", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        try {
            var selectedChannel = channelField.getValue();
            if (selectedChannel == null) {
                Notification.show("Source Channel is required", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                channelField.focus();
                return;
            }

            Long channelId = selectedChannel.getId();
            if (channelId == null) {
                Notification.show("Invalid channel selected", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            if (currentRule == null) {
                // Create new rule
                var newRule = ruleService.createRule(
                        nameField.getValue(),
                        typeField.getValue(),
                        channelId,
                        (int) (double) priorityField.getValue()
                );
                this.currentRule = newRule;
                this.ruleId = newRule.getId();
                Notification.show("Rule created", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } else {
                ruleService.updateRule(ruleId, nameField.getValue(), currentRule.getDescription(),
                        typeField.getValue(), channelId, (int) (double) priorityField.getValue(), enabledField.getValue());
                Notification.show("Rule saved", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }
            close();
            if (onSaveCallback != null) {
                onSaveCallback.accept(null);
            }
        } catch (Exception e) {
            Notification.show("Error saving rule: " + e.getMessage(), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void addCondition() {
        if (currentRule == null) {
            Notification.show("Please save the rule first before adding conditions", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_WARNING);
            return;
        }

        var dialog = new Dialog();
        dialog.setHeaderTitle("Add Condition");

        var fieldField = new TextField("Field");
        fieldField.setPlaceholder("header.sourceSystem or payload.type");
        fieldField.setRequired(true);
        fieldField.setWidthFull();
        fieldField.setMaxLength(255);
        fieldField.setHelperText("Max 255 characters");

        var operatorField = new ComboBox<ConditionOperator>("Operator");
        operatorField.setItems(ConditionOperator.values());
        operatorField.setRequired(true);
        operatorField.setWidthFull();

        var valueField = new TextField("Value");
        valueField.setRequired(true);
        valueField.setWidthFull();
        valueField.setMaxLength(500);
        valueField.setHelperText("Max 500 characters");

        var logicalOpField = new ComboBox<LogicalOperator>("Logical Operator");
        logicalOpField.setItems(LogicalOperator.values());
        logicalOpField.setValue(LogicalOperator.AND);
        logicalOpField.setWidthFull();

        var form = new VerticalLayout(fieldField, operatorField, valueField, logicalOpField);
        form.setSpacing(true);
        form.setPadding(false);

        dialog.add(form);

        var saveButton = new Button("Add", event -> {
            if (fieldField.getValue().isBlank() || operatorField.getValue() == null || valueField.getValue().isBlank()) {
                Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            ruleService.addCondition(ruleId, fieldField.getValue(), operatorField.getValue(),
                    valueField.getValue(), logicalOpField.getValue());
            refreshRule();
            dialog.close();
            Notification.show("Condition added", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void deleteCondition(RuleCondition condition) {
        ruleService.deleteCondition(condition.getId());
        refreshRule();
        Notification.show("Condition deleted", 3000, Notification.Position.BOTTOM_END);
    }

    private void addAction() {
        if (currentRule == null) {
            Notification.show("Please save the rule first before adding actions", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_WARNING);
            return;
        }

        var dialog = new Dialog();
        dialog.setHeaderTitle("Add Action");

        var typeField = new ComboBox<ActionType>("Action Type");
        typeField.setItems(ActionType.values());
        typeField.setRequired(true);
        typeField.setWidthFull();

        var targetChannelField = new TextField("Target Channel");
        targetChannelField.setVisible(false);
        targetChannelField.setWidthFull();
        targetChannelField.setRequired(true);
        targetChannelField.setMaxLength(255);

        var transformationScriptField = new TextField("Transformation Script");
        transformationScriptField.setVisible(false);
        transformationScriptField.setWidthFull();
        transformationScriptField.setRequired(true);
        transformationScriptField.setMaxLength(1000);

        var filterResultField = new Checkbox("Filter Result (pass)");
        filterResultField.setVisible(false);

        var metadataKeyField = new TextField("Metadata Key");
        metadataKeyField.setVisible(false);
        metadataKeyField.setWidthFull();
        metadataKeyField.setRequired(true);
        metadataKeyField.setMaxLength(255);

        var metadataValueField = new TextField("Metadata Value");
        metadataValueField.setVisible(false);
        metadataValueField.setWidthFull();
        metadataValueField.setRequired(true);
        metadataValueField.setMaxLength(500);

        typeField.addValueChangeListener(event -> {
            var actionType = event.getValue();
            targetChannelField.setVisible(actionType == ActionType.ROUTE);
            transformationScriptField.setVisible(actionType == ActionType.TRANSFORM);
            filterResultField.setVisible(actionType == ActionType.FILTER);
            metadataKeyField.setVisible(actionType == ActionType.ENRICH);
            metadataValueField.setVisible(actionType == ActionType.ENRICH);
        });

        var form = new VerticalLayout(typeField, targetChannelField, transformationScriptField,
                filterResultField, metadataKeyField, metadataValueField);
        form.setSpacing(true);
        form.setPadding(false);

        dialog.add(form);

        var saveButton = new Button("Add", event -> {
            if (typeField.getValue() == null) {
                Notification.show("Please select action type", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            // Validate type-specific required fields
            if (typeField.getValue() == ActionType.ROUTE && targetChannelField.getValue().isBlank()) {
                Notification.show("Target Channel is required for ROUTE action", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                targetChannelField.focus();
                return;
            }

            if (typeField.getValue() == ActionType.TRANSFORM && transformationScriptField.getValue().isBlank()) {
                Notification.show("Transformation Script is required for TRANSFORM action", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                transformationScriptField.focus();
                return;
            }

            if (typeField.getValue() == ActionType.ENRICH) {
                if (metadataKeyField.getValue().isBlank()) {
                    Notification.show("Metadata Key is required for ENRICH action", 3000, Notification.Position.BOTTOM_END)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR);
                    metadataKeyField.focus();
                    return;
                }
                if (metadataValueField.getValue().isBlank()) {
                    Notification.show("Metadata Value is required for ENRICH action", 3000, Notification.Position.BOTTOM_END)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR);
                    metadataValueField.focus();
                    return;
                }
            }

            var action = ruleService.addAction(ruleId, typeField.getValue());

            if (typeField.getValue() == ActionType.ROUTE) {
                action.setTargetChannel(targetChannelField.getValue());
            } else if (typeField.getValue() == ActionType.TRANSFORM) {
                action.setTransformationScript(transformationScriptField.getValue());
            } else if (typeField.getValue() == ActionType.FILTER) {
                action.setFilterResult(filterResultField.getValue());
            } else if (typeField.getValue() == ActionType.ENRICH) {
                action.setMetadataKey(metadataKeyField.getValue());
                action.setMetadataValue(metadataValueField.getValue());
            }

            refreshRule();
            dialog.close();
            Notification.show("Action added", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void deleteAction(RuleAction action) {
        ruleService.deleteAction(action.getId());
        refreshRule();
        Notification.show("Action deleted", 3000, Notification.Position.BOTTOM_END);
    }

    private void refreshRule() {
        if (ruleId != null) {
            currentRule = ruleService.findById(ruleId);
            if (currentRule != null) {
                conditionGrid.setItems(currentRule.getConditions());
                actionGrid.setItems(currentRule.getActions());
            }
        }
    }
}
