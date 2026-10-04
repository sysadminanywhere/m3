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
    final ComboBox<ChannelSettings> destinationField;
    final RuleFlowPreview flow = new RuleFlowPreview();
    final RuleLoadingSettings loadingField = new RuleLoadingSettings();
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
        channelField.addValueChangeListener(event -> loadingField.setChannelType(
                event.getValue() == null ? null : event.getValue().getChannelType()));
        channelField.setRequired(true);
        channelField.setWidthFull();

        destinationField = new ComboBox<>("Destination Channel");
        destinationField.setItems(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND));
        destinationField.setItemLabelGenerator(channel -> channel.getName() + (Boolean.TRUE.equals(channel.getEnabled()) ? "" : " (disabled)"));
        destinationField.setWidthFull();
        destinationField.setClearButtonVisible(true);
        channelField.addValueChangeListener(e -> updateFlow());
        destinationField.addValueChangeListener(e -> updateFlow());
        destinationField.setHelperText("Select the outbound channel that receives this message");
        typeField.addValueChangeListener(event -> {
            boolean outbound = event.getValue() == RuleType.OUTBOUND;
            destinationField.setRequired(outbound);
            channelField.setVisible(!outbound); channelField.setRequired(!outbound);
            loadingField.setVisible(event.getValue() == RuleType.INBOUND);
        });

        priorityField = new NumberField("Priority");
        priorityField.setValue(0.0);
        priorityField.setWidthFull();
        priorityField.setMin(0);
        priorityField.setMax(9999);
        priorityField.setStep(1);
        priorityField.setErrorMessage("Priority must be between 0 and 9999");

        enabledField = new Checkbox("Enabled");
        enabledField.setValue(true);

        var form = new HorizontalLayout(nameField, typeField, channelField, destinationField, priorityField, enabledField);
        form.setWrap(true);
        form.setWidthFull();

        // Conditions section
        conditionGrid = new Grid<>();
        conditionGrid.addColumn(RuleCondition::getField).setHeader("Field");
        conditionGrid.addColumn(c -> RuleConditionEditorDialog.operatorLabel(c.getOperator())).setHeader("Comparison");
        conditionGrid.addColumn(RuleCondition::getLogicalOperator).setHeader("Combine");
        conditionGrid.addColumn(RuleCondition::getValue).setHeader("Value");
        conditionGrid.addComponentColumn(condition -> {
            var deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            deleteButton.addClickListener(event -> deleteCondition(condition));
            return new HorizontalLayout(new Button("Edit", e -> new RuleConditionEditorDialog(ruleService, ruleId, condition, () -> refreshRule()).open()), deleteButton);
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
        actionGrid.addColumn(RuleAction::getPriority).setHeader("Order").setWidth("80px").setFlexGrow(0);
        actionGrid.addColumn(RuleAction::getActionType).setHeader("Type");
        actionGrid.addColumn(RuleActionEditorDialog::description).setHeader("Parameters");
        actionGrid.addComponentColumn(action -> {
            var deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            deleteButton.addClickListener(event -> deleteAction(action));
            return new HorizontalLayout(new Button("Edit", e -> new RuleActionEditorDialog(ruleService, ruleId, action, () -> refreshRule()).open()), deleteButton);
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

        loadingField.setVisible(false);
        var content = new VerticalLayout(form, loadingField, flow, conditionSection, actionSection);
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
        typeField.addValueChangeListener(e -> updateFlow());
        updateFlow();
    }

    private void loadRule(Rule rule) {
        nameField.setValue(rule.getName());
        typeField.setValue(rule.getRuleType());
        channelField.setItems(channelSettingsService.findAll());
        channelField.setValue(rule.getSourceChannel());
        priorityField.setValue((double) rule.getPriority());
        enabledField.setValue(rule.getEnabled());
        loadingField.load(rule.getLoadingProperties());
        conditionGrid.setItems(rule.getConditions());
        actionGrid.setItems(RuleActionEditorDialog.orderedActions(rule));
        destinationField.setValue(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND).stream()
                .filter(channel -> channel.getName().equals(rule.getDestinationChannelName())).findFirst().orElse(null));
        updateFlow();
    }

    private void saveRule() {
        if (nameField.getValue().isBlank() || typeField.getValue() == null || priorityField.getValue() == null) {
            Notification.show("Name, type and priority are required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }
        boolean outbound = typeField.getValue() == RuleType.OUTBOUND;
        if (outbound && destinationField.getValue() == null) {
            destinationField.setInvalid(true); destinationField.setErrorMessage("Select a destination channel"); destinationField.focus();
            return;
        }
        if (!outbound && channelField.getValue() == null) {
            channelField.setInvalid(true); channelField.setErrorMessage("Select a source channel"); channelField.focus();
            return;
        }
        double priority = priorityField.getValue();
        if (priority < 0 || priority > 9999 || priority != Math.rint(priority)) {
            priorityField.setInvalid(true); priorityField.setErrorMessage("Priority must be an integer between 0 and 9999"); return;
        }
        try {
            currentRule = ruleService.saveConfiguration(ruleId, nameField.getValue(),
                    currentRule == null ? null : currentRule.getDescription(), typeField.getValue(),
                    outbound ? null : channelField.getValue().getId(), (int) priority, enabledField.getValue(), null,
                    destinationField.getValue() == null ? null : destinationField.getValue().getId(), loadingField.settings());
            ruleId = currentRule.getId();
            Notification.show("Rule saved", 3000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            close();
            if (onSaveCallback != null) onSaveCallback.accept(null);
        } catch (Exception error) {
            Notification.show("Error saving rule: " + error.getMessage(), 4000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
    private void addCondition() {
        if (currentRule == null) {
            Notification.show("Please save the rule first", 3000, Notification.Position.BOTTOM_END); return;
        }
        new RuleConditionEditorDialog(ruleService, ruleId, null, () -> refreshRule()).open();
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
        new RuleActionEditorDialog(ruleService, ruleId, () -> refreshRule()).open();
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
                actionGrid.setItems(RuleActionEditorDialog.orderedActions(currentRule));
                updateFlow();
            }
        }
    }
    private void updateFlow() {
        flow.show(currentRule, typeField.getValue() == RuleType.OUTBOUND ? null : channelField.getValue(), destinationField.getValue());
    }
}
