package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.ViewTitle;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
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

@Route(value = "rules/:ruleId")
class RuleDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;
    private Long ruleId;
    private Rule currentRule;

    final TextField nameField;
    final ComboBox<RuleType> typeField;
    final ComboBox<ChannelSettings> channelField;
    final com.vaadin.flow.component.textfield.NumberField priorityField;
    final com.vaadin.flow.component.checkbox.Checkbox enabledField;
    final Grid<RuleCondition> conditionGrid;
    final Grid<RuleAction> actionGrid;

    RuleDetailView(RuleService ruleService, ChannelSettingsService channelSettingsService) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;

        nameField = new TextField("Name");
        nameField.setRequired(true);

        typeField = new ComboBox<RuleType>("Type");
        typeField.setItems(RuleType.values());
        typeField.setRequired(true);

        channelField = new ComboBox<>("Source Channel");
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.setRequired(true);

        priorityField = new com.vaadin.flow.component.textfield.NumberField("Priority");
        priorityField.setValue(0.0);

        enabledField = new com.vaadin.flow.component.checkbox.Checkbox("Enabled");
        enabledField.setValue(true);

        var form = new HorizontalLayout(nameField, typeField, channelField, priorityField, enabledField);
        form.setWrap(true);
        form.setWidthFull();

        var saveButton = new Button("Save", event -> saveRule());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var backButton = new Button("Back to Rules", event -> getUI().ifPresent(ui -> ui.navigate(RuleListView.class)));

        var toolbar = new HorizontalLayout(new ViewTitle("Rule Details"), saveButton, backButton);
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
        actionGrid.addColumn(RuleAction::getTargetChannel).setHeader("Target Channel");
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
        add(toolbar, form, conditionSection, actionSection);
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
            conditionGrid.setItems(currentRule.getConditions());
            actionGrid.setItems(currentRule.getActions());
        }
    }

    private void saveRule() {
        if (nameField.getValue().isBlank() || typeField.getValue() == null || channelField.getValue() == null) {
            Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        ruleService.updateRule(ruleId, nameField.getValue(), currentRule.getDescription(),
                typeField.getValue(), channelField.getValue().getId(), (int) (double) priorityField.getValue(), enabledField.getValue());
        Notification.show("Rule saved", 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
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

        var logicalOpField = new ComboBox<LogicalOperator>("Logical Operator");
        logicalOpField.setItems(LogicalOperator.values());
        logicalOpField.setValue(LogicalOperator.AND);

        var form = new VerticalLayout(fieldField, operatorField, valueField, logicalOpField);
        form.setSpacing(true);

        var saveButton = new Button("Add", event -> {
            if (fieldField.getValue().isBlank() || operatorField.getValue() == null || valueField.getValue().isBlank()) {
                Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            ruleService.addCondition(ruleId, fieldField.getValue(), operatorField.getValue(),
                    valueField.getValue(), logicalOpField.getValue());
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
        var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle("Add Action");

        var typeField = new ComboBox<ActionType>("Action Type");
        typeField.setItems(ActionType.values());
        typeField.setRequired(true);
        typeField.addValueChangeListener(event -> updateActionDialogFields(event.getValue(), dialog));

        var targetChannelField = new TextField("Target Channel");
        targetChannelField.setVisible(false);

        var transformationScriptField = new TextField("Transformation Script");
        transformationScriptField.setVisible(false);

        var filterResultField = new com.vaadin.flow.component.checkbox.Checkbox("Filter Result (pass)");
        filterResultField.setVisible(false);

        var metadataKeyField = new TextField("Metadata Key");
        metadataKeyField.setVisible(false);

        var metadataValueField = new TextField("Metadata Value");
        metadataValueField.setVisible(false);

        var form = new VerticalLayout(typeField, targetChannelField, transformationScriptField,
                filterResultField, metadataKeyField, metadataValueField);
        form.setSpacing(true);

        var saveButton = new Button("Add", event -> {
            if (typeField.getValue() == null) {
                Notification.show("Please select action type", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
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

            loadRule();
            dialog.close();
            Notification.show("Action added", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        var footer = new HorizontalLayout(saveButton, cancelButton);
        dialog.add(form, footer);
        dialog.open();
    }

    private void updateActionDialogFields(ActionType actionType, com.vaadin.flow.component.dialog.Dialog dialog) {
        var form = (VerticalLayout) dialog.getChildren().findFirst().get();
        
        for (var component : form.getChildren().toList()) {
            if (component instanceof TextField tf) {
                if (!tf.getLabel().equals("Action Type")) {
                    tf.setVisible(false);
                }
            } else if (component instanceof com.vaadin.flow.component.checkbox.Checkbox cb) {
                cb.setVisible(false);
            }
        }

        if (actionType == ActionType.ROUTE) {
            form.getChildren().filter(c -> c instanceof TextField && ((TextField) c).getLabel().equals("Target Channel"))
                    .findFirst().ifPresent(c -> c.setVisible(true));
        } else if (actionType == ActionType.TRANSFORM) {
            form.getChildren().filter(c -> c instanceof TextField && ((TextField) c).getLabel().equals("Transformation Script"))
                    .findFirst().ifPresent(c -> c.setVisible(true));
        } else if (actionType == ActionType.FILTER) {
            form.getChildren().filter(c -> c instanceof com.vaadin.flow.component.checkbox.Checkbox)
                    .findFirst().ifPresent(c -> c.setVisible(true));
        } else if (actionType == ActionType.ENRICH) {
            form.getChildren().filter(c -> c instanceof TextField && ((TextField) c).getLabel().equals("Metadata Key"))
                    .findFirst().ifPresent(c -> c.setVisible(true));
            form.getChildren().filter(c -> c instanceof TextField && ((TextField) c).getLabel().equals("Metadata Value"))
                    .findFirst().ifPresent(c -> c.setVisible(true));
        }
    }

    private void deleteAction(RuleAction action) {
        ruleService.deleteAction(action.getId());
        loadRule();
        Notification.show("Action deleted", 3000, Notification.Position.BOTTOM_END);
    }
}
