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
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.HasDynamicTitle;

@Route(value = "rules/:ruleId")
class RuleDetailView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private final RuleService ruleService;
    private final ChannelSettingsService channelSettingsService;
    private final RuleWorkerPoolService workerPoolService;
    private Long ruleId;
    private Rule currentRule;

    final TextField nameField;
    final ComboBox<RuleType> typeField;
    final ComboBox<ChannelSettings> channelField;
    final ComboBox<ChannelSettings> destinationField;
    final RuleFlowPreview flow = new RuleFlowPreview();
    final RuleLoadingSettings loadingField = new RuleLoadingSettings();
    final com.vaadin.flow.component.textfield.NumberField priorityField;
    final com.vaadin.flow.component.checkbox.Checkbox enabledField;
    final ComboBox<com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool> workerPoolField;

    RuleDetailView(RuleService ruleService, ChannelSettingsService channelSettingsService,
                   RuleWorkerPoolService workerPoolService) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;
        this.workerPoolService = workerPoolService;

        nameField = new TextField(t("Name"));
        nameField.setRequired(true);

        typeField = new ComboBox<RuleType>(t("Type"));
        typeField.setItems(RuleType.values()); typeField.setItemLabelGenerator(Translations::enumLabel);
        typeField.setRequired(true);

        channelField = new ComboBox<>(t("Source Channel"));
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.addValueChangeListener(event -> loadingField.setChannelType(
                event.getValue() == null ? null : event.getValue().getChannelType()));
        channelField.setRequired(true);

        destinationField = new ComboBox<>(t("Destination Channel"));
        destinationField.setItems(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND));
        destinationField.setItemLabelGenerator(channel -> channel.getName() + (Boolean.TRUE.equals(channel.getEnabled()) ? "" : t(" (disabled)")));
        destinationField.setClearButtonVisible(true);
        channelField.addValueChangeListener(e -> updateFlow());
        destinationField.addValueChangeListener(e -> updateFlow());
        destinationField.setHelperText(t("Select the outbound channel that receives this message"));
        typeField.addValueChangeListener(event -> {
            boolean outbound = event.getValue() == RuleType.OUTBOUND;
            destinationField.setRequired(outbound);
            channelField.setVisible(!outbound); channelField.setRequired(!outbound);
            loadingField.setVisible(event.getValue() == RuleType.INBOUND);
        });

        priorityField = new com.vaadin.flow.component.textfield.NumberField(t("Priority"));
        priorityField.setValue(0.0);

        enabledField = new com.vaadin.flow.component.checkbox.Checkbox(t("Enabled"));
        enabledField.setValue(true);

        workerPoolField = new ComboBox<>(t("Worker pool"));
        workerPoolField.setItems(workerPoolService.findAll());
        workerPoolField.setItemLabelGenerator(com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool::getName);
        workerPoolField.setRequired(true);
        workerPoolField.setHelperText(t("Choose where this rule will execute"));

        var form = new HorizontalLayout(nameField, typeField, priorityField, enabledField);
        form.setWrap(true);
        form.setWidthFull();

        var saveButton = new Button(t("Save"), event -> saveRule());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var backButton = new Button(t("Back to Rules"), event -> getUI().ifPresent(ui -> ui.navigate(RuleListView.class)));

        var toolbar = new HorizontalLayout(saveButton, backButton);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        setSizeFull();
        loadingField.setVisible(false);
        flow.configure(ruleService, this::refreshSteps, channelField, destinationField, loadingField, workerPoolField);
        add(toolbar, form, flow);
        setHeightFull();
        getStyle().set("overflow-y", "auto");
        typeField.addValueChangeListener(e -> updateFlow());
        updateFlow();
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
            destinationField.setValue(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND).stream()
                    .filter(channel -> channel.getName().equals(currentRule.getDestinationChannelName())).findFirst().orElse(null));
            updateFlow();
        }
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
                    workerPoolField.getValue().getId(), destinationField.getValue() == null ? null : destinationField.getValue().getId(), loadingField.settings());
            loadRule();
            Notification.show(t("Rule saved"), 3000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception error) {
            Notification.show(t("Error saving rule: ") + t(error.getMessage()), 4000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void refreshSteps() {
        currentRule = ruleService.findById(ruleId);
        if (currentRule != null) {
            updateFlow();
        }
    }
    private void updateFlow() {
        flow.show(currentRule, typeField.getValue() == RuleType.OUTBOUND ? null : channelField.getValue(), destinationField.getValue());
    }
    @Override public String getPageTitle() { return t("Rule Details"); }
}
