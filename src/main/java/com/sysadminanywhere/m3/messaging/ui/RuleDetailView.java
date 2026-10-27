package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.sysadminanywhere.m3.messaging.service.RuleWorkerPoolService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.HasDynamicTitle;

@jakarta.annotation.security.RolesAllowed("ADMIN")
@Route(value = "rules/:ruleId")
class RuleDetailView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private java.util.List<com.sysadminanywhere.m3.extensions.RuleFeature> features = java.util.List.of();
    private final HorizontalLayout featureControls = new HorizontalLayout();
    private com.sysadminanywhere.m3.base.ui.UnsavedChangesGuard guard;

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;
    private final RuleWorkerPoolService workerPoolService;
    private final com.sysadminanywhere.m3.messaging.service.SystemOverviewCache overview;
    private final com.sysadminanywhere.m3.messaging.source.SourceHealth sourceHealth;
    private final com.vaadin.flow.component.html.Span readiness = new com.vaadin.flow.component.html.Span();
    private final com.vaadin.flow.component.html.Span receiverStatus = new com.vaadin.flow.component.html.Span();
    private Long ruleId;
    private Rule currentRule;
    private final RuleStepsPanel stepsPanel = new RuleStepsPanel();

    final TextField nameField;
    final ComboBox<RuleType> typeField;
    final ComboBox<ChannelSettings> channelField;
    final ComboBox<ChannelSettings> destinationField;
    final RadioButtonGroup<OutboundPayloadMode> outboundPayloadModeField;
    final RuleLoadingSettings loadingField = new RuleLoadingSettings();
    final com.vaadin.flow.component.textfield.NumberField priorityField;
    final com.vaadin.flow.component.checkbox.Checkbox enabledField;
    final ComboBox<com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool> workerPoolField;

    RuleDetailView(RuleService ruleService, ChannelSettingsService channelSettingsService,
                   RuleWorkerPoolService workerPoolService,
                   com.sysadminanywhere.m3.messaging.service.SystemOverviewCache overview,
                   com.sysadminanywhere.m3.messaging.source.SourceHealth sourceHealth) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;
        this.workerPoolService = workerPoolService;
        this.overview = overview;
        this.sourceHealth = sourceHealth;

        nameField = new TextField(t("Name"));
        nameField.setRequired(true);

        typeField = new ComboBox<RuleType>(t("Type"));
        typeField.setItems(RuleType.values()); typeField.setItemLabelGenerator(Translations::enumLabel);
        typeField.setRequired(true);

        channelField = new ComboBox<>(t("Source Channel"));
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.setWidthFull();
        channelField.addValueChangeListener(event -> loadingField.setChannelType(
                event.getValue() == null ? null : event.getValue().getChannelType()));
        channelField.setRequired(true);

        destinationField = new ComboBox<>(t("Destination Channel"));
        destinationField.setItems(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND));
        destinationField.setItemLabelGenerator(channel -> channel.getName() + (Boolean.TRUE.equals(channel.getEnabled()) ? "" : t(" (disabled)")));
        destinationField.setClearButtonVisible(true);
        destinationField.setWidthFull();
        destinationField.setVisible(true);
        destinationField.setHelperText(t("Select the outbound channel that receives this message"));
        outboundPayloadModeField = new RadioButtonGroup<>(t("Target receives"));
        outboundPayloadModeField.setItems(OutboundPayloadMode.values());
        outboundPayloadModeField.setItemLabelGenerator(mode -> t(mode == OutboundPayloadMode.MESSAGE_ID ? "Message ID" : "Original message body"));
        outboundPayloadModeField.setValue(OutboundPayloadMode.BODY);
        outboundPayloadModeField.setHelperText(t("Choose whether the target receives the message body or its ID. In ID mode, it can download the original bytes from /api/v1/messages/{id}/payload."));
        outboundPayloadModeField.setEnabled(true);
        typeField.addValueChangeListener(event -> {
            boolean outbound = event.getValue() == RuleType.OUTBOUND;
            destinationField.setRequired(outbound);
            destinationField.setVisible(true);
            channelField.setVisible(!outbound); channelField.setRequired(!outbound);
            loadingField.setVisible(event.getValue() == RuleType.INBOUND);
        });

        priorityField = new com.vaadin.flow.component.textfield.NumberField(t("Priority"));
        priorityField.setRequired(true);
        priorityField.setValue(0.0);

        enabledField = new com.vaadin.flow.component.checkbox.Checkbox(t("Enabled"));
        enabledField.setValue(true);

        workerPoolField = new ComboBox<>(t("Worker pool"));
        workerPoolField.setItems(workerPoolService.findAll());
        workerPoolField.setItemLabelGenerator(com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool::getName);
        workerPoolField.setRequired(true);
        workerPoolField.setHelperText(t("Choose where this rule will execute"));

        var form = new FormLayout(nameField, typeField, priorityField, enabledField,
                channelField, destinationField, outboundPayloadModeField, workerPoolField);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("760px", 2));
        form.setWidthFull();
        form.setColspan(outboundPayloadModeField, 2);

        var saveButton = new Button(t("Save"), event -> saveRule());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var backButton = new Button(t("Back to Rules"), event -> getUI().ifPresent(ui -> ui.navigate(RuleListView.class)));

        var toolbar = new HorizontalLayout(saveButton, backButton);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        setSizeFull();
        loadingField.setVisible(false);
        stepsPanel.configure(ruleService, this::refreshSteps);
        readiness.getElement().setAttribute("role", "status");
        var refreshReadiness = new Button(t("Refresh"), event -> { overview.invalidate(); refreshReadiness(); });
        receiverStatus.getElement().setAttribute("role", "status");
        var readinessPanel = new VerticalLayout(new HorizontalLayout(readiness, refreshReadiness), receiverStatus);
        readinessPanel.setPadding(false); readinessPanel.setSpacing(false); readinessPanel.setWidthFull();
        add(toolbar, featureControls, readinessPanel, form, loadingField, stepsPanel);
        guard = new com.sysadminanywhere.m3.base.ui.UnsavedChangesGuard(this, form, loadingField);
        toolbar.add(guard.indicator());
        setHeightFull();
        getStyle().set("overflow-y", "auto");
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
        featureControls.removeAll();
        if (currentRule != null) features.forEach(feature -> {
            var control = feature.control(currentRule);
            if (control != null) featureControls.add(control);
        });
        stepsPanel.show(currentRule);
        if (currentRule != null) {
            nameField.setValue(currentRule.getName());
            typeField.setValue(currentRule.getRuleType());
            outboundPayloadModeField.setValue(currentRule.getOutboundPayloadMode());
            channelField.setItems(channelSettingsService.findAll());
            channelField.setValue(currentRule.getSourceChannel());
            priorityField.setValue((double) currentRule.getPriority());
            enabledField.setValue(currentRule.getEnabled());
            loadingField.load(currentRule.getLoadingProperties());
            workerPoolField.setItems(workerPoolService.findAll());
            workerPoolField.setValue(currentRule.getWorkerPool());
            destinationField.setValue(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND).stream()
                    .filter(channel -> channel.getName().equals(currentRule.getDestinationChannelName())).findFirst().orElse(null));
        }
        guard.markSaved();
        refreshReadiness();
    }

    private void refreshReadiness() {
        try {
            readiness.setText(t(readinessMessage(currentRule, () -> overview.snapshot().pools(),
                    () -> channelSettingsService.findByDirection(ChannelDirection.OUTBOUND))));
        } catch (RuntimeException unavailable) { readiness.setText(t("Rule readiness is unavailable.")); }
        receiverStatus.setVisible(currentRule != null && currentRule.getRuleType() == RuleType.INBOUND
                && Boolean.TRUE.equals(currentRule.getEnabled()));
        if (receiverStatus.isVisible()) {
            try {
                var state = sourceHealth.get(currentRule.getId());
                receiverStatus.setText(t("Receiver") + ": " + t(state.status())
                        + (state.error() == null ? "" : " · " + t(state.error())));
            } catch (RuntimeException unavailable) { receiverStatus.setText(t("Rule readiness is unavailable.")); }
        }
    }

    static String readinessMessage(Rule rule, java.util.function.Supplier<java.util.List<com.sysadminanywhere.m3.messaging.service.SystemOverviewService.PoolStatus>> pools,
            java.util.function.Supplier<java.util.List<ChannelSettings>> destinations) {
        if (rule == null) return "Rule readiness is unavailable.";
        if (!Boolean.TRUE.equals(rule.getEnabled())) return "Saved rule is disabled; it will not process messages.";
        if (rule.getRuleType() == RuleType.INBOUND && (rule.getSourceChannel() == null
                || !Boolean.TRUE.equals(rule.getSourceChannel().getEnabled())))
            return "The saved source channel is disabled or missing.";
        var target = rule.getDestinationChannelName();
        if (target == null && rule.getRuleType() == RuleType.OUTBOUND)
            return "The saved destination channel is disabled or missing.";
        if (target != null && destinations.get().stream().noneMatch(channel -> target.equals(channel.getName())
                && Boolean.TRUE.equals(channel.getEnabled())))
            return "The saved destination channel is disabled or missing.";
        if (rule.getWorkerPool() == null || rule.getWorkerPool().getId() == null) return "The saved rule has no worker pool.";
        var pool = pools.get().stream().filter(item -> item.id() == rule.getWorkerPool().getId()).findFirst();
        return pool.isPresent() && pool.get().activeWorkers() > 0
                ? "Saved rule: a worker heartbeat is available. Check channel connectivity before sending."
                : "Saved rule: no recent worker heartbeat in its pool. Start a worker and refresh.";
    }

    private void refreshSteps() {
        currentRule = ruleService.findById(ruleId);
        stepsPanel.show(currentRule);
    }

    private void saveRule() {
        if (nameField.getValue().isBlank() || typeField.getValue() == null || priorityField.getValue() == null || workerPoolField.getValue() == null) {
            Notification.show(t("Please fill all required fields"), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        boolean outbound = typeField.getValue() == RuleType.OUTBOUND;
        if (outbound && destinationField.getValue() == null) {
            destinationField.setInvalid(true); destinationField.setErrorMessage(t("Select a destination channel")); destinationField.focus(); return;
        }
        if (!outbound && channelField.getValue() == null) {
            channelField.setInvalid(true); channelField.setErrorMessage(t("Select a source channel")); channelField.focus(); return;
        }
        double priority = priorityField.getValue();
        if (priority < 0 || priority > 9999 || priority != Math.rint(priority)) {
            priorityField.setInvalid(true); priorityField.setErrorMessage(t("Priority must be an integer between 0 and 9999")); return;
        }
        try {
            ruleService.saveConfiguration(ruleId, nameField.getValue(), currentRule.getDescription(), typeField.getValue(),
                    outbound ? null : channelField.getValue().getId(), (int) priority, enabledField.getValue(),
                    workerPoolField.getValue().getId(), destinationField.getValue() == null ? null : destinationField.getValue().getId(),
                    outboundPayloadModeField.getValue(), loadingField.settings(), currentRule == null ? null : currentRule.getVersion());
            loadRule();
            Notification.show(t("Rule saved"), 3000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception error) {
            Notification.show(t("Error saving rule: ") + com.sysadminanywhere.m3.base.ui.SaveErrors.message(error), 4000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    @Override public String getPageTitle() { return t("Rule Details"); }
}
