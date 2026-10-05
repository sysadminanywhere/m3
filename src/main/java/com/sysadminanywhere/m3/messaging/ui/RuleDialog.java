package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
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

    public RuleDialog(RuleService ruleService, ChannelSettingsService channelSettingsService, Consumer<Void> onSaveCallback) {
        this(ruleService, channelSettingsService, null, onSaveCallback);
    }

    public RuleDialog(RuleService ruleService, ChannelSettingsService channelSettingsService, Rule rule, Consumer<Void> onSaveCallback) {
        this.ruleService = ruleService;
        this.channelSettingsService = channelSettingsService;
        this.currentRule = rule;
        this.ruleId = rule != null ? rule.getId() : null;
        this.onSaveCallback = onSaveCallback;

        setHeaderTitle(rule == null ? t("Add Rule") : t("Edit Rule"));
        setWidth("1100px");
        setMaxWidth("calc(100vw - 32px)");
        setHeight("90%");

        nameField = new TextField(t("Name"));
        nameField.setRequired(true);
        nameField.setWidthFull();
        nameField.setMaxLength(100);
        nameField.setHelperText(t("Max 100 characters"));
        nameField.setValueChangeMode(ValueChangeMode.EAGER);
        nameField.addValueChangeListener(event -> {
            if (event.getValue().length() > 100) {
                nameField.setInvalid(true);
                nameField.setErrorMessage(t("Name cannot exceed 100 characters"));
            } else {
                nameField.setInvalid(false);
            }
        });

        typeField = new ComboBox<>(t("Type"));
        typeField.setItems(RuleType.values()); typeField.setItemLabelGenerator(Translations::enumLabel);
        typeField.setRequired(true);
        typeField.setWidthFull();

        channelField = new ComboBox<>(t("Source Channel"));
        channelField.setItems(channelSettingsService.findAll());
        channelField.setItemLabelGenerator(ChannelSettings::getName);
        channelField.addValueChangeListener(event -> loadingField.setChannelType(
                event.getValue() == null ? null : event.getValue().getChannelType()));
        channelField.setRequired(true);
        channelField.setWidthFull();

        destinationField = new ComboBox<>(t("Destination Channel"));
        destinationField.setItems(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND));
        destinationField.setItemLabelGenerator(channel -> channel.getName() + (Boolean.TRUE.equals(channel.getEnabled()) ? "" : t(" (disabled)")));
        destinationField.setWidthFull();
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

        priorityField = new NumberField(t("Priority"));
        priorityField.setValue(0.0);
        priorityField.setWidthFull();
        priorityField.setMin(0);
        priorityField.setMax(9999);
        priorityField.setStep(1);
        priorityField.setErrorMessage(t("Priority must be between 0 and 9999"));

        enabledField = new Checkbox(t("Enabled"));
        enabledField.setValue(true);

        var form = new HorizontalLayout(nameField, typeField, priorityField, enabledField);
        form.setWrap(true);
        form.setWidthFull();

        loadingField.setVisible(false);
        flow.configure(ruleService, this::refreshRule, channelField, destinationField, loadingField, null);
        var content = new VerticalLayout(form, flow);
        content.setSpacing(true);
        content.setPadding(false);
        content.setWidthFull();
        content.getStyle().set("overflow-y", "auto");

        add(content);

        // Footer buttons
        var saveButton = new Button(t("Save"), event -> saveRule());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button(t("Cancel"), event -> close());

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
        destinationField.setValue(channelSettingsService.findByDirection(ChannelDirection.OUTBOUND).stream()
                .filter(channel -> channel.getName().equals(rule.getDestinationChannelName())).findFirst().orElse(null));
        updateFlow();
    }

    private void saveRule() {
        if (nameField.getValue().isBlank() || typeField.getValue() == null || priorityField.getValue() == null) {
            Notification.show(t("Name, type and priority are required"), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }
        boolean outbound = typeField.getValue() == RuleType.OUTBOUND;
        if (outbound && destinationField.getValue() == null) {
            destinationField.setInvalid(true); destinationField.setErrorMessage(t("Select a destination channel")); destinationField.focus();
            return;
        }
        if (!outbound && channelField.getValue() == null) {
            channelField.setInvalid(true); channelField.setErrorMessage(t("Select a source channel")); channelField.focus();
            return;
        }
        double priority = priorityField.getValue();
        if (priority < 0 || priority > 9999 || priority != Math.rint(priority)) {
            priorityField.setInvalid(true); priorityField.setErrorMessage(t("Priority must be an integer between 0 and 9999")); return;
        }
        try {
            currentRule = ruleService.saveConfiguration(ruleId, nameField.getValue(),
                    currentRule == null ? null : currentRule.getDescription(), typeField.getValue(),
                    outbound ? null : channelField.getValue().getId(), (int) priority, enabledField.getValue(), null,
                    destinationField.getValue() == null ? null : destinationField.getValue().getId(), loadingField.settings());
            ruleId = currentRule.getId();
            Notification.show(t("Rule saved"), 3000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            close();
            if (onSaveCallback != null) onSaveCallback.accept(null);
        } catch (Exception error) {
            Notification.show(t("Error saving rule: ") + t(error.getMessage()), 4000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
    private void refreshRule() {
        if (ruleId != null) {
            currentRule = ruleService.findById(ruleId);
            if (currentRule != null) {
                updateFlow();
            }
        }
    }
    private void updateFlow() {
        flow.show(currentRule, typeField.getValue() == RuleType.OUTBOUND ? null : channelField.getValue(), destinationField.getValue());
    }
}
