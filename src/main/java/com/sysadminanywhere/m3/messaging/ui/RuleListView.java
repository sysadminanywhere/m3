package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.ViewTitle;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.domain.RuleType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@Route(value = "rules")
@PageTitle("Rules")
@MenuItem(order = 3, icon = "icons/rule.svg", title = "Rules", section = MenuSection.MESSAGING)
class RuleListView extends VerticalLayout {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;

    final Button createBtn;
    final Grid<Rule> ruleGrid;

    RuleListView(RuleService ruleService, ChannelSettingsService channelSettingsService) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;

        createBtn = new Button("Create Rule", event -> openCreateDialog());
        createBtn.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout();
        toolbar.add(new ViewTitle("Rules"), createBtn);
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        ruleGrid = new Grid<>();
        ruleGrid.setItems(query -> {
            var pageRequest = toSpringPageRequest(query);
            var rules = ruleService.findAll();
            return rules.stream()
                    .skip(pageRequest.getOffset())
                    .limit(pageRequest.getPageSize());
        });
        ruleGrid.addColumn(Rule::getName).setHeader("Name");
        ruleGrid.addColumn(Rule::getRuleType).setHeader("Type");
        ruleGrid.addColumn(rule -> rule.getSourceChannel() != null ? rule.getSourceChannel().getName() : "N/A").setHeader("Source Channel");
        ruleGrid.addColumn(Rule::getPriority).setHeader("Priority");
        ruleGrid.addComponentColumn(rule -> {
            var enabledIcon = rule.getEnabled() ? VaadinIcon.CHECK.create() : VaadinIcon.CLOSE.create();
            var button = new Button(enabledIcon);
            button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            button.addClickListener(event -> toggleRuleEnabled(rule));
            return button;
        }).setHeader("Enabled");
        ruleGrid.addComponentColumn(rule -> {
            var editButton = new Button(new Icon(VaadinIcon.EDIT));
            editButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            editButton.addClickListener(event -> openEditDialog(rule));
            return editButton;
        }).setHeader("Actions");
        ruleGrid.setEmptyStateText("No rules configured");
        ruleGrid.setSizeFull();

        setSizeFull();
        add(toolbar, ruleGrid);
    }

    private void openCreateDialog() {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Create Rule");

        var nameField = new com.vaadin.flow.component.textfield.TextField("Name");
        nameField.setRequired(true);

        var typeField = new ComboBox<RuleType>("Type");
        typeField.setItems(RuleType.values());
        typeField.setRequired(true);

        var channelField = new ComboBox<ChannelSettings>("Source Channel");
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.setRequired(true);

        var priorityField = new com.vaadin.flow.component.textfield.NumberField("Priority");
        priorityField.setValue(0.0);

        var form = new VerticalLayout(nameField, typeField, channelField, priorityField);
        form.setSpacing(true);

        var saveButton = new Button("Save", event -> {
            if (nameField.getValue().isBlank() || typeField.getValue() == null || channelField.getValue() == null) {
                Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            ruleService.createRule(nameField.getValue(), typeField.getValue(), channelField.getValue().getId(), priorityField.getValue().intValue());
            ruleGrid.getDataProvider().refreshAll();
            dialog.close();
            Notification.show("Rule created", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        var footer = new HorizontalLayout(saveButton, cancelButton);
        dialog.add(form, footer);
        dialog.open();
    }

    private void openEditDialog(Rule rule) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Edit Rule");

        var nameField = new com.vaadin.flow.component.textfield.TextField("Name");
        nameField.setValue(rule.getName());
        nameField.setRequired(true);

        var typeField = new ComboBox<RuleType>("Type");
        typeField.setItems(RuleType.values());
        typeField.setValue(rule.getRuleType());
        typeField.setRequired(true);

        var channelField = new ComboBox<ChannelSettings>("Source Channel");
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.setValue(rule.getSourceChannel());
        channelField.setRequired(true);

        var priorityField = new com.vaadin.flow.component.textfield.NumberField("Priority");
        priorityField.setValue((double) rule.getPriority());

        var form = new VerticalLayout(nameField, typeField, channelField, priorityField);
        form.setSpacing(true);

        var saveButton = new Button("Save", event -> {
            if (nameField.getValue().isBlank() || typeField.getValue() == null || channelField.getValue() == null) {
                Notification.show("Please fill all required fields", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }

            ruleService.updateRule(rule.getId(), nameField.getValue(), rule.getDescription(),
                    typeField.getValue(), channelField.getValue().getId(), priorityField.getValue().intValue(), rule.getEnabled());
            ruleGrid.getDataProvider().refreshAll();
            dialog.close();
            Notification.show("Rule updated", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        var footer = new HorizontalLayout(saveButton, cancelButton);
        dialog.add(form, footer);
        dialog.open();
    }

    private void toggleRuleEnabled(Rule rule) {
        ruleService.toggleEnabled(rule.getId());
        ruleGrid.getDataProvider().refreshAll();
        Notification.show("Rule " + (rule.getEnabled() ? "enabled" : "disabled"), 3000, Notification.Position.BOTTOM_END);
    }
}
