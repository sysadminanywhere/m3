package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.domain.RuleType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.sysadminanywhere.m3.base.ui.ResponsiveGrid;
import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@jakarta.annotation.security.RolesAllowed("ADMIN")
@Route(value = "rules")
@MenuItem(order = 3, icon = "icons/rule.svg", title = "Rules", section = MenuSection.ADMINISTRATION)
class RuleListView extends VerticalLayout implements HasDynamicTitle {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;

    final Button createBtn;
    final Grid<Rule> ruleGrid;

    RuleListView(RuleService ruleService, ChannelSettingsService channelSettingsService, com.sysadminanywhere.m3.messaging.source.SourceHealth sourceHealth,
            com.sysadminanywhere.m3.messaging.service.WorkerPoolCapacityService capacity) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;

        createBtn = new Button(t("Create Rule"), event -> openCreateDialog());
        createBtn.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout();
        toolbar.add(createBtn);
        if (!capacity.isConfigured()) {
            var hint = new com.vaadin.flow.component.html.Span(t("UI does not execute rules. Start a separate worker for the assigned pool: scripts/run-worker.ps1 -Pool default, or configure Docker Compose."));
            hint.getStyle().set("color", "var(--lumo-error-text-color)").set("white-space", "normal");
            toolbar.add(hint);
        }
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        ruleGrid = new Grid<>();
        ruleGrid.setItems(query -> {
            var pageRequest = toSpringPageRequest(query);
            return ruleService.page(org.springframework.data.domain.PageRequest.of(pageRequest.getPageNumber(),
                    pageRequest.getPageSize(), org.springframework.data.domain.Sort.by("id"))).stream();
        });
        ruleGrid.addColumn(Rule::getName).setHeader(t("Name")).setWidth("120px").setFlexGrow(1);
        ruleGrid.addColumn(item -> Translations.enumLabel(item.getRuleType())).setHeader(t("Type")).setWidth("120px").setFlexGrow(0);
        ruleGrid.addColumn(rule -> rule.getSourceChannel() != null ? rule.getSourceChannel().getName() : "N/A")
                .setHeader(t("Source")).setWidth("135px").setFlexGrow(1);
        ruleGrid.addComponentColumn(rule -> {
            String pool = rule.getWorkerPool() != null ? rule.getWorkerPool().getName() : t("Unassigned");
            var label = new com.vaadin.flow.component.html.Span(pool);
            var receiver = rule.getRuleType() == RuleType.INBOUND ? sourceHealth.get(rule.getId()) : null;
            if (receiver != null) label.getElement().setAttribute("title", pool + " · "
                    + t(receiver.status()) + (receiver.error() == null ? "" : " · " + t(receiver.error())));
            return label;
        }).setHeader(t("Pool")).setWidth("105px").setFlexGrow(0);
        ruleGrid.addColumn(rule -> rule.getDestinationChannelName() == null ? t("Not selected") : rule.getDestinationChannelName())
                .setHeader(t("Target")).setWidth("120px").setFlexGrow(1);
        ruleGrid.addComponentColumn(rule -> {
            String label = rule.getDestinationChannelName() == null ? t("Not selected")
                    : t(rule.getOutboundPayloadMode() == com.sysadminanywhere.m3.messaging.domain.OutboundPayloadMode.MESSAGE_ID
                    ? "Message ID" : "Original message body");
            var value = new com.vaadin.flow.component.html.Span(rule.getDestinationChannelName() == null ? "—"
                    : t(rule.getOutboundPayloadMode() == com.sysadminanywhere.m3.messaging.domain.OutboundPayloadMode.MESSAGE_ID ? "ID" : "Message body"));
            value.getElement().setAttribute("title", label);
            return value;
        }).setHeader(t("Delivery format")).setWidth("88px").setFlexGrow(0);
        ruleGrid.addColumn(Rule::getPriority).setHeader(t("Priority")).setWidth("100px").setFlexGrow(0);
        ruleGrid.addComponentColumn(rule -> {
            var enabledIcon = rule.getEnabled() ? VaadinIcon.CHECK.create() : VaadinIcon.CLOSE.create();
            var button = new Button(enabledIcon);
            button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            button.setTooltipText(t(rule.getEnabled() ? "Enabled" : "Disabled"));
            button.setAriaLabel(t(rule.getEnabled() ? "Enabled" : "Disabled"));
            button.addClickListener(event -> toggleRuleEnabled(rule));
            return button;
        }).setHeader(t("Enabled")).setWidth("105px").setFlexGrow(0);
        ruleGrid.addComponentColumn(rule -> {
            var editButton = new Button(new Icon(VaadinIcon.EDIT));
            editButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            editButton.setTooltipText(t("Edit"));
            editButton.setAriaLabel(t("Edit"));
            editButton.addClickListener(event -> openEditDialog(rule));
            var configureButton = new Button(t("Configure"), event -> getUI().ifPresent(ui -> ui.navigate("rules/" + rule.getId())));
            configureButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            return new HorizontalLayout(editButton, configureButton);
        }).setHeader(t("Actions")).setWidth("140px").setFlexGrow(0).setFrozenToEnd(true);
        ruleGrid.setEmptyStateText(t("No rules configured"));
        ruleGrid.setSizeFull();
        ResponsiveGrid.configure(ruleGrid);

        setSizeFull();
        addAttachListener(event -> {
            var ui = event.getUI();
            ui.setPollInterval(10000);
            var registration = ui.addPollListener(poll -> ruleGrid.getDataProvider().refreshAll());
            addDetachListener(detach -> { registration.remove(); ui.setPollInterval(-1); });
        });
        add(toolbar, ruleGrid);
        getStyle().set("overflow-y", "auto");
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
            Notification.show(t("Rule ") + (updated.getEnabled() ? t("enabled") : t("disabled")), 3000, Notification.Position.BOTTOM_END);
        } catch (IllegalArgumentException error) {
            Notification.show(t(error.getMessage()), 5000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
    @Override public String getPageTitle() { return t("Rules"); }
}
