package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.sysadminanywhere.m3.messaging.service.RuleWorkerPoolService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.PageTitle;

@Route(value = "rules/:ruleId")
@PageTitle("Rule Details")
class RuleDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;
    private final RuleWorkerPoolService workerPoolService;
    private Long ruleId;
    private Rule currentRule;

    final TextField nameField;
    final ComboBox<RuleType> typeField;
    final ComboBox<ChannelSettings> channelField;
    final ComboBox<ChannelSettings> destinationField;
    final RuleLoadingSettings loadingField = new RuleLoadingSettings();
    final com.vaadin.flow.component.textfield.NumberField priorityField;
    final com.vaadin.flow.component.checkbox.Checkbox enabledField;
    final ComboBox<com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool> workerPoolField;
    final Grid<RuleCondition> conditionGrid;
    final Grid<RuleAction> actionGrid;

    RuleDetailView(RuleService ruleService, ChannelSettingsService channelSettingsService,
                   RuleWorkerPoolService workerPoolService) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;
        this.workerPoolService = workerPoolService;

        nameField = new TextField("Name");
        nameField.setRequired(true);

        typeField = new ComboBox<RuleType>("Type");
        typeField.setItems(RuleType.values());
        typeField.setRequired(true);

        channelField = new ComboBox<>("Source Channel");
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.setRequired(true);

        destinationField = new ComboBox<>("Destination Channel");
        destinationField.setItems(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND));
        destinationField.setItemLabelGenerator(channel -> channel.getName() + (Boolean.TRUE.equals(channel.getEnabled()) ? "" : " (disabled)"));
        destinationField.setClearButtonVisible(true);
        destinationField.setHelperText("Select the outbound channel that receives this message");
        typeField.addValueChangeListener(event -> {
            boolean outbound = event.getValue() == RuleType.OUTBOUND;
            destinationField.setRequired(outbound);
            channelField.setVisible(!outbound); channelField.setRequired(!outbound);
            loadingField.setVisible(event.getValue() == RuleType.INBOUND);
        });

        priorityField = new com.vaadin.flow.component.textfield.NumberField("Priority");
        priorityField.setValue(0.0);

        enabledField = new com.vaadin.flow.component.checkbox.Checkbox("Enabled");
        enabledField.setValue(true);

        workerPoolField = new ComboBox<>("Worker pool");
        workerPoolField.setItems(workerPoolService.findAll());
        workerPoolField.setItemLabelGenerator(com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool::getName);
        workerPoolField.setRequired(true);
        workerPoolField.setHelperText("Choose where this rule will execute");

        var form = new HorizontalLayout(nameField, typeField, channelField, destinationField, workerPoolField, priorityField, enabledField);
        form.setWrap(true);
        form.setWidthFull();

        var saveButton = new Button("Save", event -> saveRule());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var backButton = new Button("Back to Rules", event -> getUI().ifPresent(ui -> ui.navigate(RuleListView.class)));

        var toolbar = new HorizontalLayout(saveButton, backButton);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

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

        var addConditionButton = new Button("Add Condition", event -> addCondition());

        var conditionSection = new VerticalLayout(conditionGrid, addConditionButton);

        actionGrid = new Grid<>();
        actionGrid.addColumn(RuleAction::getActionType).setHeader("Type");
        actionGrid.addColumn(RuleActionEditorDialog::description).setHeader("Parameters");
        actionGrid.addComponentColumn(action -> {
            var deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            deleteButton.addClickListener(event -> deleteAction(action));
            return deleteButton;
        }).setHeader("Actions");
        actionGrid.setEmptyStateText("No actions");

        var addActionButton = new Button("Add Action", event -> addAction());

        var actionSection = new VerticalLayout(actionGrid, addActionButton);

        setSizeFull();
        loadingField.setVisible(false);
        add(toolbar, form, loadingField, conditionSection, actionSection);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var routeParams = event.getRouteParameters();
        routeParams.get("ruleId").ifPresent(id -> {
            this.ruleId = Long.parseLong(id);
            loadRule();
        });
    }

    private void loadRule() {
        currentRule = ruleService.findById(ruleId);
        if (currentRule != null) {
            nameField.setValue(currentRule.getName());
            typeField.setValue(currentRule.getRuleType());
            channelField.setItems(channelSettingsService.findAll());
            channelField.setValue(currentRule.getSourceChannel());
            priorityField.setValue((double) currentRule.getPriority());
            enabledField.setValue(currentRule.getEnabled());
            loadingField.load(currentRule.getLoadingProperties());
            workerPoolField.setItems(workerPoolService.findAll());
            workerPoolField.setValue(currentRule.getWorkerPool());
            conditionGrid.setItems(currentRule.getConditions());
            actionGrid.setItems(currentRule.getActions().stream().filter(action -> action.getActionType() != ActionType.ROUTE).toList());
            destinationField.setValue(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND).stream()
                    .filter(channel -> channel.getName().equals(currentRule.getDestinationChannelName())).findFirst().orElse(null));
        }
    }

    private void saveRule() {
        if (nameField.getValue().isBlank() || typeField.getValue() == null || priorityField.getValue() == null || workerPoolField.getValue() == null) {
            Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        boolean outbound = typeField.getValue() == RuleType.OUTBOUND;
        if (outbound && destinationField.getValue() == null) {
            destinationField.setInvalid(true); destinationField.setErrorMessage("Select a destination channel"); destinationField.focus(); return;
        }
        if (!outbound && channelField.getValue() == null) {
            channelField.setInvalid(true); channelField.setErrorMessage("Select a source channel"); channelField.focus(); return;
        }
        double priority = priorityField.getValue();
        if (priority < 0 || priority > 9999 || priority != Math.rint(priority)) {
            priorityField.setInvalid(true); priorityField.setErrorMessage("Priority must be an integer between 0 and 9999"); return;
        }
        try {
            ruleService.saveConfiguration(ruleId, nameField.getValue(), currentRule.getDescription(), typeField.getValue(),
                    outbound ? null : channelField.getValue().getId(), (int) priority, enabledField.getValue(),
                    workerPoolField.getValue().getId(), destinationField.getValue() == null ? null : destinationField.getValue().getId(), loadingField.settings());
            loadRule();
            Notification.show("Rule saved", 3000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception error) {
            Notification.show("Error saving rule: " + error.getMessage(), 4000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void addCondition() {
        var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle("Add Condition");

        var fieldField = new TextField("Field");
        fieldField.setPlaceholder("header.sourceSystem or payload.type");
        fieldField.setRequired(true);

        var operatorField = new ComboBox<ConditionOperator>("Operator");
        operatorField.setItems(ConditionOperator.values());
        operatorField.setRequired(true);

        var valueField = new TextField("Value");
        valueField.setRequired(true);

        var logicalOpField = new ComboBox<LogicalOperator>("Combine with previous conditions");
        logicalOpField.setItems(LogicalOperator.values());
        logicalOpField.setValue(LogicalOperator.AND);
        logicalOpField.setHelperText("Conditions follow creation order. AND has priority over OR; the first connector is ignored.");

        var form = new VerticalLayout(fieldField, operatorField, valueField, logicalOpField);
        form.setSpacing(true);

        var saveButton = new Button("Add", event -> {
            if (fieldField.getValue().isBlank() || operatorField.getValue() == null || valueField.getValue().isBlank()) {
                Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            try {
                ruleService.addCondition(ruleId, fieldField.getValue(), operatorField.getValue(),
                        valueField.getValue(), logicalOpField.getValue());
            } catch (IllegalArgumentException invalid) {
                Notification.show(invalid.getMessage(),4000,Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            loadRule();
            dialog.close();
            Notification.show("Condition added", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        var footer = new HorizontalLayout(saveButton, cancelButton);
        dialog.add(form, footer);
        dialog.open();
    }

    private void deleteCondition(RuleCondition condition) {
        ruleService.deleteCondition(condition.getId());
        loadRule();
        Notification.show("Condition deleted", 3000, Notification.Position.BOTTOM_END);
    }

    private void addAction() {
        if (currentRule == null) {
            Notification.show("Please save the rule first before adding actions", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_WARNING);
            return;
        }
        new RuleActionEditorDialog(ruleService, ruleId, () -> loadRule()).open();
    }
    private void deleteAction(RuleAction action) {
        ruleService.deleteAction(action.getId());
        loadRule();
        Notification.show("Action deleted", 3000, Notification.Position.BOTTOM_END);
    }
}
