package com.sysadminanywhere.m3.messaging.ui;

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
@MenuItem(order = 1, icon = "icons/rule.svg", title = "Rules", section = MenuSection.SETTINGS)
class RuleListView extends VerticalLayout {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;

    final Button createBtn;
    final Grid<Rule> ruleGrid;

    RuleListView(RuleService ruleService, ChannelSettingsService channelSettingsService, com.sysadminanywhere.m3.messaging.source.SourceHealth sourceHealth) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;

        createBtn = new Button("Create Rule", event -> openCreateDialog());
        createBtn.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout();
        toolbar.add(createBtn);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        ruleGrid = new Grid<>();
        ruleGrid.setItems(query -> {
            var pageRequest = toSpringPageRequest(query);
            return ruleService.page(org.springframework.data.domain.PageRequest.of(pageRequest.getPageNumber(),
                    pageRequest.getPageSize(), org.springframework.data.domain.Sort.by("id"))).stream();
        });
        ruleGrid.addColumn(Rule::getName).setHeader("Name").setWidth("100px").setFlexGrow(1);
        ruleGrid.addColumn(Rule::getRuleType).setHeader("Type").setWidth("82px").setFlexGrow(0);
        ruleGrid.addColumn(rule -> rule.getSourceChannel() != null ? rule.getSourceChannel().getName() : "N/A")
                .setHeader("Source Channel").setWidth("130px").setFlexGrow(1);
        ruleGrid.addColumn(rule -> rule.getWorkerPool() != null ? rule.getWorkerPool().getName() : "Unassigned")
                .setHeader("Worker Pool").setWidth("120px").setFlexGrow(0);
        ruleGrid.addColumn(rule -> rule.getDestinationChannelName() == null ? "Not selected" : rule.getDestinationChannelName())
                .setHeader("Destination Channel").setWidth("150px").setFlexGrow(1);
        ruleGrid.addComponentColumn(rule -> {
            var state = rule.getRuleType() == RuleType.INBOUND ? sourceHealth.get(rule.getId()) : null;
            var label = new com.vaadin.flow.component.html.Span(state == null ? "—"
                    : Boolean.TRUE.equals(rule.getEnabled()) ? state.status() : "DISABLED");
            if (state != null) label.getElement().setAttribute("title", state.error() == null
                    ? "Last heartbeat: " + state.checkedAt() : state.error());
            return label;
        })
                .setHeader("Receiver").setWidth("110px").setFlexGrow(0);
        ruleGrid.addColumn(Rule::getPriority).setHeader("Priority").setWidth("72px").setFlexGrow(0);
        ruleGrid.addComponentColumn(rule -> {
            var enabledIcon = rule.getEnabled() ? VaadinIcon.CHECK.create() : VaadinIcon.CLOSE.create();
            var button = new Button(enabledIcon);
            button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            button.addClickListener(event -> toggleRuleEnabled(rule));
            return button;
        }).setHeader("Enabled").setWidth("76px").setFlexGrow(0);
        ruleGrid.addComponentColumn(rule -> {
            var editButton = new Button(new Icon(VaadinIcon.EDIT));
            editButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            editButton.addClickListener(event -> openEditDialog(rule));
            var configureButton = new Button("Configure", event -> getUI().ifPresent(ui -> ui.navigate("rules/" + rule.getId())));
            configureButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            return new HorizontalLayout(editButton, configureButton);
        }).setHeader("Actions").setWidth("160px").setFlexGrow(0);
        ruleGrid.setEmptyStateText("No rules configured");
        ruleGrid.setSizeFull();

        setSizeFull();
        addAttachListener(event -> {
            var ui = event.getUI();
            ui.setPollInterval(10000);
            var registration = ui.addPollListener(poll -> ruleGrid.getDataProvider().refreshAll());
            addDetachListener(detach -> { registration.remove(); ui.setPollInterval(-1); });
        });
        add(toolbar, ruleGrid);
    }

    private void openCreateDialog() {
        var dialog = new RuleDialog(ruleService, channelSettingsService, v -> refreshGrid());
        dialog.open();
    }

    private void openEditDialog(Rule rule) {
        var dialog = new RuleDialog(ruleService, channelSettingsService, rule, v -> refreshGrid());
        dialog.open();
    }

    private void refreshGrid() {
        ruleGrid.getDataProvider().refreshAll();
    }

    private void toggleRuleEnabled(Rule rule) {
        try {
            ruleService.toggleEnabled(rule.getId());
            var updated = ruleService.findById(rule.getId());
            ruleGrid.getDataProvider().refreshAll();
            Notification.show("Rule " + (updated.getEnabled() ? "enabled" : "disabled"), 3000, Notification.Position.BOTTOM_END);
        } catch (IllegalArgumentException error) {
            Notification.show(error.getMessage(), 5000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
}
