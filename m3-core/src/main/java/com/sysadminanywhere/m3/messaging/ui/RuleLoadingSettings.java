package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.source.FilePayloads;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import java.util.*;

final class RuleLoadingSettings extends VerticalLayout {
    private final ComboBox<com.sysadminanywhere.m3.messaging.domain.MessageStatus> initialStatus = new ComboBox<>(t("Initial message status"));
    private final TextField recipients=new TextField(t("Processing recipients"));
    private final IntegerField loadedTimeout=positiveField(t("Loaded timeout (seconds)"),86400);
    private final IntegerField processingTimeout=positiveField(t("Processing timeout (seconds)"),3600);
    private final IntegerField polling = positiveField(t("Polling interval (ms)"), 5000);
    private final IntegerField minimumAge = positiveField(t("Minimum file age (ms)"), 1000);
    private final TextField pattern = new TextField(t("File name pattern"));
    private final Checkbox recursive = new Checkbox(t("Include subdirectories"));
    private final Checkbox deleteSourceFile = new Checkbox(t("Delete source file after loading"));
    private final Span deleteHint = new Span(t("The file is deleted only after its original bytes, metadata and processing job are saved in the database. Delivery happens separately; the original message remains in the database."));
    private final TextField group = new TextField(t("Kafka consumer group"));
    private final ComboBox<String> offset = new ComboBox<>(t("Initial offset policy"));
    private final VerticalLayout files = new VerticalLayout();
    private final HorizontalLayout kafka = new HorizontalLayout(group, offset);
    private final Span brokerHint = new Span(t("Messages are acknowledged after saving in the database. Connection and queue settings belong to the channel."));
    private ChannelType channelType;

    RuleLoadingSettings() {
        setWidthFull(); setPadding(false); setSpacing(true);
        pattern.setValue("*"); pattern.setHelperText(t("Glob pattern, for example *.xml or *.txt"));
        pattern.setWidth("250px");
        var fileFields = new HorizontalLayout(polling, minimumAge, pattern);
        fileFields.setWrap(true); fileFields.setWidthFull();
        files.setPadding(false); files.setWidthFull();
        files.add(fileFields, recursive, deleteSourceFile, deleteHint);
        deleteHint.getStyle().set("white-space", "normal");
        group.setWidth("280px"); group.setPlaceholder(t("Automatic: m3-rule-<id>"));
        group.setHelperText(t("Leave empty to use a separate consumer group for this rule"));
        offset.setItems("earliest", "latest", "none"); offset.setValue("earliest");
        offset.setRequired(true);
        offset.setHelperText(t("Used when the group has no committed offset"));
        kafka.setWrap(true); kafka.setWidthFull();
        initialStatus.setItems(java.util.Arrays.stream(com.sysadminanywhere.m3.messaging.domain.MessageStatus.values()).filter(com.sysadminanywhere.m3.messaging.domain.MessageStatus::isInboundProcessingStatus).toList());
        initialStatus.setItemLabelGenerator(Translations::enumLabel);
        initialStatus.setValue(com.sysadminanywhere.m3.messaging.domain.MessageStatus.LOADED);
        initialStatus.setRequired(true);
        initialStatus.setWidth("320px");
        initialStatus.setMaxWidth("100%");
        initialStatus.setHelperText(t("Routing does not complete external processing. The external service updates the message status through the API."));
        recipients.setValue("external");recipients.setWidthFull();recipients.setHelperText(t("Comma-separated recipient keys; append ? for an optional recipient. All required recipients must succeed."));
        var deadlines=new HorizontalLayout(loadedTimeout,processingTimeout);deadlines.setWrap(true);deadlines.setWidthFull();
        add(new Span(t("Loading settings")), initialStatus,recipients,deadlines, files, kafka, brokerHint);
        setChannelType(null);
    }
    private static IntegerField positiveField(String label, int defaultValue) {
        var field = new IntegerField(t(label));
        field.setMin(1); field.setStep(1); field.setValue(defaultValue);
        field.setRequired(true);
        field.setErrorMessage(t("Enter a positive whole number"));
        field.addValueChangeListener(event -> {
            if (event.getValue() != null && event.getValue() > 0) field.setInvalid(false);
        });
        return field;
    }
    void setChannelType(ChannelType type) {
        channelType = type;
        boolean file = type == ChannelType.DIRECTORY || type == ChannelType.FTP || type == ChannelType.SFTP;
        files.setVisible(file); recursive.setVisible(type == ChannelType.DIRECTORY);
        kafka.setVisible(type == ChannelType.KAFKA);
        brokerHint.setVisible(type == ChannelType.RABBITMQ);
    }
    void load(Map<String,String> values) {
        initialStatus.setValue(com.sysadminanywhere.m3.messaging.domain.MessageStatus.valueOf(values.getOrDefault("initialStatus", "LOADED")));
        recipients.setValue(values.getOrDefault("processingRecipients","external"));loadNumber(loadedTimeout,values.getOrDefault("loadedTimeoutSeconds","86400"));loadNumber(processingTimeout,values.getOrDefault("processingTimeoutSeconds","3600"));
        loadNumber(polling, values.getOrDefault("pollingInterval", "5000"));
        loadNumber(minimumAge, values.getOrDefault("minFileAgeMs", "1000"));
        pattern.setValue(values.getOrDefault("filePattern", "*"));
        recursive.setValue(Boolean.parseBoolean(values.getOrDefault("recursive", "false")));
        deleteSourceFile.setValue(Boolean.parseBoolean(values.getOrDefault("deleteAfterProcessing", "false"))
                || Boolean.parseBoolean(values.getOrDefault("deleteRemoteFiles", "false")));
        group.setValue(values.getOrDefault("groupId", ""));
        String policy = values.getOrDefault("autoOffsetReset", "earliest");
        offset.setValue(Set.of("earliest", "latest", "none").contains(policy) ? policy : null);
    }
    private static void loadNumber(IntegerField field, String value) {
        try { field.setValue(Integer.valueOf(value)); }
        catch (NumberFormatException invalid) { field.clear(); field.setInvalid(true); }
    }
    private static String number(IntegerField field) {
        if (field.getValue() == null || field.getValue() < 1 || field.isInvalid()) {
            field.setInvalid(true);
            throw new IllegalArgumentException(field.getLabel() + t(" must be a positive whole number"));
        }
        return field.getValue().toString();
    }
    Map<String,String> settings() {
        if (!isVisible()) return Map.of();
        var result = new HashMap<String,String>();
        if (initialStatus.getValue() == null) throw new IllegalArgumentException(t("Select an initial message status"));
        result.put("initialStatus", initialStatus.getValue().name());
        result.put("processingRecipients",recipients.getValue().trim());result.put("loadedTimeoutSeconds",number(loadedTimeout));result.put("processingTimeoutSeconds",number(processingTimeout));
        if (files.isVisible()) {
            result.put("pollingInterval", number(polling));
            result.put("minFileAgeMs", number(minimumAge));
            String selectedPattern = pattern.getValue().isBlank() ? "*" : pattern.getValue();
            try { FilePayloads.matches(selectedPattern, "example"); pattern.setInvalid(false); }
            catch (IllegalArgumentException invalid) {
                pattern.setInvalid(true); pattern.setErrorMessage(t("Invalid file name pattern"));
                throw new IllegalArgumentException(t("Invalid file name pattern"));
            }
            result.put("filePattern", selectedPattern);
            if (channelType == ChannelType.DIRECTORY) result.put("recursive", recursive.getValue().toString());
            result.put(channelType == ChannelType.DIRECTORY ? "deleteAfterProcessing" : "deleteRemoteFiles", deleteSourceFile.getValue().toString());
        } else if (kafka.isVisible()) {
            if (!group.getValue().isBlank()) result.put("groupId", group.getValue().trim());
            if (offset.getValue() == null) {
                offset.setInvalid(true); offset.setErrorMessage(t("Select an offset policy"));
                throw new IllegalArgumentException(t("Select a Kafka offset policy"));
            }
            offset.setInvalid(false);
            result.put("autoOffsetReset", offset.getValue());
        }
        return result;
    }
}
