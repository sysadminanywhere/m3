package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ChannelDialog extends Dialog {

    private final ChannelSettingsService channelSettingsService;
    private final Consumer<Void> onSaveCallback;
    private Long channelId;
    private ChannelSettings currentChannel;

    final TextField nameField;
    final ComboBox<ChannelType> typeField;
    final ComboBox<ChannelDirection> directionField;
    final TextArea descriptionField;
    final Checkbox enabledField;
    final Tabs propertyTabs;

    private final ChannelExtraSettings extraSettings=new ChannelExtraSettings();
    private final Map<String, TextField> textFields = new HashMap<>();
    private final Map<String, PasswordField> passwordFields = new HashMap<>();
    private final Map<String, NumberField> numberFields = new HashMap<>();
    private final Map<String, Checkbox> checkboxes = new HashMap<>();

    private final VerticalLayout connectionTabContent;
    private final VerticalLayout advancedTabContent;

    public ChannelDialog(ChannelSettingsService channelSettingsService, Consumer<Void> onSaveCallback) {
        this(channelSettingsService, null, onSaveCallback);
    }

    public ChannelDialog(ChannelSettingsService channelSettingsService, ChannelSettings channel, Consumer<Void> onSaveCallback) {
        this.channelSettingsService = channelSettingsService;
        this.currentChannel = channel;
        this.channelId = channel != null ? channel.getId() : null;
        this.onSaveCallback = onSaveCallback;

        setHeaderTitle(channel == null ? "Add Channel" : "Edit Channel");
        setMinWidth("600px");
        setMaxWidth("800px");
        setHeight("80%");

        nameField = new TextField("Name");
        nameField.setRequired(true);
        nameField.setWidthFull();
        nameField.setMaxLength(ChannelSettings.NAME_MAX_LENGTH);
        nameField.setHelperText("Max " + ChannelSettings.NAME_MAX_LENGTH + " characters");
        nameField.setValueChangeMode(ValueChangeMode.EAGER);
        nameField.addValueChangeListener(event -> {
            if (event.getValue().length() > ChannelSettings.NAME_MAX_LENGTH) {
                nameField.setInvalid(true);
                nameField.setErrorMessage("Name cannot exceed " + ChannelSettings.NAME_MAX_LENGTH + " characters");
            } else {
                nameField.setInvalid(false);
            }
        });

        typeField = new ComboBox<>("Type");
        typeField.setItems(ChannelType.values());
        typeField.setRequired(true);
        typeField.addValueChangeListener(event -> onTypeChanged(event.getValue()));

        directionField = new ComboBox<>("Direction");
        directionField.setItems(ChannelDirection.values());
        directionField.setValue(ChannelDirection.INBOUND);
        directionField.setRequired(true);

        descriptionField = new TextArea("Description");
        descriptionField.setWidthFull();
        descriptionField.setMaxLength(ChannelSettings.DESCRIPTION_MAX_LENGTH);
        descriptionField.setHelperText("Max " + ChannelSettings.DESCRIPTION_MAX_LENGTH + " characters");

        enabledField = new Checkbox("Outbound channel available");
        enabledField.setValue(true);
        enabledField.setVisible(directionField.getValue()==ChannelDirection.OUTBOUND);
        directionField.addValueChangeListener(event -> enabledField.setVisible(event.getValue()==ChannelDirection.OUTBOUND));

        var basicForm = new FormLayout();
        basicForm.add(nameField, typeField, directionField, enabledField);
        basicForm.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));
        basicForm.setWidthFull();

        connectionTabContent = new VerticalLayout();
        connectionTabContent.setSpacing(true);
        connectionTabContent.setPadding(true);

        advancedTabContent = new VerticalLayout();
        advancedTabContent.setSpacing(true);
        advancedTabContent.setPadding(true);

        var connectionTab = new Tab("Connection");
        var advancedTab = new Tab("Advanced");
        propertyTabs = new Tabs(connectionTab, advancedTab);
        propertyTabs.addSelectedChangeListener(event -> updateTabContent());

        var tabContentWrapper = new VerticalLayout();
        tabContentWrapper.add(propertyTabs, connectionTabContent, advancedTabContent);
        tabContentWrapper.setPadding(false);
        tabContentWrapper.setSpacing(false);
        tabContentWrapper.setWidthFull();

        var content = new VerticalLayout(basicForm, descriptionField, tabContentWrapper);
        content.setSpacing(true);
        content.setPadding(false);
        content.setWidthFull();

        add(content);

        // Footer buttons
        var saveButton = new Button("Save", event -> saveChannel());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var cancelButton = new Button("Cancel", event -> close());

        getFooter().add(cancelButton, saveButton);

        // Load data if editing
        if (channel != null) {
            loadChannel(channel);
        } else {
            clearForm();
        }
    }

    private void clearForm() {
        typeField.setEnabled(true);
        directionField.setEnabled(true);
        nameField.clear();
        typeField.clear();
        directionField.setValue(ChannelDirection.INBOUND);
        descriptionField.clear();
        enabledField.setValue(true);
        clearPropertyFields();
    }

    private void loadChannel(ChannelSettings channel) {
        nameField.setValue(channel.getName());
        typeField.setValue(channel.getChannelType());
        directionField.setValue(channel.getDirection());
        descriptionField.setValue(channel.getDescription() != null ? channel.getDescription() : "");
        enabledField.setValue(channel.getEnabled());

        loadPropertyFields(channel.getProperties());
        typeField.setEnabled(false);
        directionField.setEnabled(false);
    }

    private void onTypeChanged(ChannelType type) {
        clearPropertyFields();
        if (type != null) {
            var defaultProps = channelSettingsService.getDefaultPropertiesForType(type);
            loadPropertyFields(defaultProps);
        }
    }

    private void clearPropertyFields() {
        textFields.clear();
        passwordFields.clear();
        numberFields.clear();
        checkboxes.clear();
        connectionTabContent.removeAll();
        advancedTabContent.removeAll();
    }

    private void loadPropertyFields(Map<String, String> properties) {
        clearPropertyFields();

        if (typeField.getValue() == null) return;

        var type = typeField.getValue();

        switch (type) {
            case FTP -> setupFtpFields(properties);
            case SFTP -> setupSftpFields(properties);
            case KAFKA -> setupKafkaFields(properties);
            case DIRECTORY -> setupDirectoryFields(properties);
            case RABBITMQ -> setupRabbitMqFields(properties);
        }

        for (String key : java.util.stream.Stream.concat(com.sysadminanywhere.m3.messaging.source.InboundSourceSpec.LOADING_KEYS.stream(),
                java.util.stream.Stream.of("keyDeserializer","valueDeserializer")).toList()) {
            com.vaadin.flow.component.Component field = textFields.remove(key);
            if (field == null) field = numberFields.remove(key);
            if (field == null) field = checkboxes.remove(key);
            if (field != null && field.getParent().orElse(null) instanceof com.vaadin.flow.component.HasComponents parent) parent.remove(field);
        }
        advancedTabContent.add(
                createTextField("charset", "Source charset (blank = unknown)", properties.getOrDefault("charset", "")),
                createTextField("outputCharset", "Transformed output charset (optional)", properties.getOrDefault("outputCharset", "")));
        var visible=new java.util.HashSet<>(textFields.keySet());
        visible.addAll(passwordFields.keySet()); visible.addAll(numberFields.keySet()); visible.addAll(checkboxes.keySet());
        extraSettings.load(properties,visible); advancedTabContent.add(extraSettings);
        updateTabContent();
    }

    private void setupFtpFields(Map<String, String> properties) {
        var hostField = createTextField("host", "Host", properties.getOrDefault("host", ""));
        var portField = createNumberField("port", "Port", Double.parseDouble(properties.getOrDefault("port", "21")));
        var usernameField = createTextField("username", "Username", properties.getOrDefault("username", ""));
        var passwordField = createPasswordField("password", "Password", properties.getOrDefault("password", ""));
        var remoteDirField = createTextField("remoteDirectory", "Remote Directory", properties.getOrDefault("remoteDirectory", "/"));
        var filePatternField = createTextField("filePattern", "File Pattern", properties.getOrDefault("filePattern", "*"));

        connectionTabContent.add(hostField, portField, usernameField, passwordField, remoteDirField, filePatternField);

        var passiveModeField = createCheckbox("passiveMode", "Passive Mode", Boolean.parseBoolean(properties.getOrDefault("passiveMode", "true")));
        var deleteRemoteField = createCheckbox("deleteRemoteFiles", "Delete Remote Files", Boolean.parseBoolean(properties.getOrDefault("deleteRemoteFiles", "false")));

        advancedTabContent.add(passiveModeField, deleteRemoteField,
                createNumberField("pollingInterval", "Polling Interval (ms)", Double.parseDouble(properties.getOrDefault("pollingInterval", "5000"))),
                createNumberField("minFileAgeMs", "Minimum File Age (ms)", Double.parseDouble(properties.getOrDefault("minFileAgeMs", "1000"))));
    }

    private void setupSftpFields(Map<String, String> properties) {
        var hostField = createTextField("host", "Host", properties.getOrDefault("host", ""));
        var portField = createNumberField("port", "Port", Double.parseDouble(properties.getOrDefault("port", "22")));
        var usernameField = createTextField("username", "Username", properties.getOrDefault("username", ""));
        var passwordField = createPasswordField("password", "Password", properties.getOrDefault("password", ""));
        var privateKeyField = createTextField("privateKey", "Private Key Path", properties.getOrDefault("privateKey", ""));
        var remoteDirField = createTextField("remoteDirectory", "Remote Directory", properties.getOrDefault("remoteDirectory", "/"));
        var filePatternField = createTextField("filePattern", "File Pattern", properties.getOrDefault("filePattern", "*"));

        connectionTabContent.add(hostField, portField, usernameField, passwordField, privateKeyField, remoteDirField, filePatternField);

        var deleteRemoteField = createCheckbox("deleteRemoteFiles", "Delete Remote Files", Boolean.parseBoolean(properties.getOrDefault("deleteRemoteFiles", "false")));

        advancedTabContent.add(deleteRemoteField,
                createTextField("knownHostsPath", "Known Hosts File", properties.getOrDefault("knownHostsPath", "")),
                createCheckbox("allowUnknownKeys", "Allow Unknown Host Keys", Boolean.parseBoolean(properties.getOrDefault("allowUnknownKeys", "false"))),
                createPasswordField("privateKeyPassphrase", "Private Key Passphrase", properties.getOrDefault("privateKeyPassphrase", "")),
                createNumberField("pollingInterval", "Polling Interval (ms)", Double.parseDouble(properties.getOrDefault("pollingInterval", "5000"))),
                createNumberField("minFileAgeMs", "Minimum File Age (ms)", Double.parseDouble(properties.getOrDefault("minFileAgeMs", "1000"))));
    }

    private void setupKafkaFields(Map<String, String> properties) {
        var bootstrapField = createTextField("bootstrapServers", "Bootstrap Servers", properties.getOrDefault("bootstrapServers", "localhost:9092"));
        var topicField = createTextField("topic", "Topic", properties.getOrDefault("topic", ""));
        var groupIdField = createTextField("groupId", "Group ID", properties.getOrDefault("groupId", "m3-consumer"));

        connectionTabContent.add(bootstrapField, topicField, groupIdField);

        var autoOffsetField = createTextField("autoOffsetReset", "Auto Offset Reset", properties.getOrDefault("autoOffsetReset", "earliest"));
        var keyDeserializerField = createTextField("keyDeserializer", "Key Deserializer", properties.getOrDefault("keyDeserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
        var valueDeserializerField = createTextField("valueDeserializer", "Value Deserializer", properties.getOrDefault("valueDeserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"));

        advancedTabContent.add(autoOffsetField, keyDeserializerField, valueDeserializerField,
                createTextField("kafka.security.protocol", "Security Protocol", properties.getOrDefault("kafka.security.protocol", "PLAINTEXT")),
                createTextField("kafka.sasl.mechanism", "SASL Mechanism", properties.getOrDefault("kafka.sasl.mechanism", "")),
                createPasswordField("kafka.sasl.jaas.config", "SASL JAAS Configuration", properties.getOrDefault("kafka.sasl.jaas.config", "")),
                createTextField("kafka.ssl.truststore.location", "SSL Truststore Path", properties.getOrDefault("kafka.ssl.truststore.location", "")),
                createPasswordField("kafka.ssl.truststore.password", "SSL Truststore Password", properties.getOrDefault("kafka.ssl.truststore.password", "")));
    }

    private void setupDirectoryFields(Map<String, String> properties) {
        var directoryPathField = createTextField("directoryPath", "Directory Path", properties.getOrDefault("directoryPath", ""));
        var filePatternField = createTextField("filePattern", "File Pattern", properties.getOrDefault("filePattern", "*"));

        connectionTabContent.add(directoryPathField, filePatternField);

        var pollingIntervalField = createNumberField("pollingInterval", "Polling Interval (ms)", Double.parseDouble(properties.getOrDefault("pollingInterval", "5000")));
        var deleteAfterProcessingField = createCheckbox("deleteAfterProcessing", "Delete After Processing", Boolean.parseBoolean(properties.getOrDefault("deleteAfterProcessing", "false")));
        var recursiveField = createCheckbox("recursive", "Recursive Scan", Boolean.parseBoolean(properties.getOrDefault("recursive", "false")));

        advancedTabContent.add(pollingIntervalField, deleteAfterProcessingField, recursiveField,
                createNumberField("minFileAgeMs", "Minimum File Age (ms)", Double.parseDouble(properties.getOrDefault("minFileAgeMs", "1000"))));
    }

    private void setupRabbitMqFields(Map<String, String> properties) {
        var hostField = createTextField("host", "Host", properties.getOrDefault("host", "localhost"));
        var portField = createNumberField("port", "Port", Double.parseDouble(properties.getOrDefault("port", "5672")));
        var usernameField = createTextField("username", "Username", properties.getOrDefault("username", "guest"));
        var passwordField = createPasswordField("password", "Password", properties.getOrDefault("password", "guest"));
        var virtualHostField = createTextField("virtualHost", "Virtual Host", properties.getOrDefault("virtualHost", "/"));

        connectionTabContent.add(hostField, portField, usernameField, passwordField, virtualHostField);

        var queueField = createTextField("queue", "Queue Name", properties.getOrDefault("queue", ""));
        var exchangeField = createTextField("exchange", "Exchange Name", properties.getOrDefault("exchange", ""));
        var routingKeyField = createTextField("routingKey", "Routing Key", properties.getOrDefault("routingKey", ""));

        advancedTabContent.add(queueField, exchangeField, routingKeyField,
                createCheckbox("declareQueue", "Declare Durable Queue and Binding", Boolean.parseBoolean(properties.getOrDefault("declareQueue", "false"))));
    }

    private TextField createTextField(String key, String label, String value) {
        var field = new TextField(label);
        field.setValue(value);
        field.setWidthFull();
        textFields.put(key, field);
        return field;
    }

    private PasswordField createPasswordField(String key, String label, String value) {
        var field = new PasswordField(label);
        field.setValue(value);
        field.setWidthFull();
        passwordFields.put(key, field);
        return field;
    }

    private NumberField createNumberField(String key, String label, Double value) {
        var field = new NumberField(label);
        field.setValue(value);
        field.setWidthFull();
        field.setMin(1);
        field.setMax(65535);
        field.setStep(1);
        field.setErrorMessage("Port must be between 1 and 65535");
        numberFields.put(key, field);
        return field;
    }

    private Checkbox createCheckbox(String key, String label, boolean value) {
        var field = new Checkbox(label);
        field.setValue(value);
        checkboxes.put(key, field);
        return field;
    }

    private void updateTabContent() {
        var selectedTab = propertyTabs.getSelectedTab();
        var tabIndex = propertyTabs.getChildren().toList().indexOf(selectedTab);

        connectionTabContent.setVisible(tabIndex == 0);
        advancedTabContent.setVisible(tabIndex == 1);
    }

    private Map<String, String> collectProperties() {
        Map<String, String> properties = new HashMap<>(extraSettings.settings());

        textFields.forEach((key, field) -> properties.put(key, field.getValue()));
        passwordFields.forEach((key, field) -> properties.put(key, field.getValue()));
        numberFields.forEach((key,field) -> {
            Double value=field.getValue();
            if (value==null || value<1 || value>65535 || value!=Math.rint(value))
                throw new IllegalArgumentException(key+" must be an integer between 1 and 65535");
            properties.put(key,Integer.toString(value.intValue()));
        });
        checkboxes.forEach((key, field) -> properties.put(key, String.valueOf(field.getValue())));

        return properties;
    }

    private void saveChannel() {
        // Validate required fields
        if (nameField.getValue().isBlank()) {
            Notification.show("Name is required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            nameField.focus();
            return;
        }

        if (typeField.getValue() == null) {
            Notification.show("Type is required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            typeField.focus();
            return;
        }

        if (directionField.getValue() == null) {
            Notification.show("Direction is required", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            directionField.focus();
            return;
        }

        // Validate name length
        if (nameField.getValue().length() > ChannelSettings.NAME_MAX_LENGTH) {
            Notification.show("Name cannot exceed " + ChannelSettings.NAME_MAX_LENGTH + " characters", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            nameField.focus();
            return;
        }

        // Validate description length
        if (descriptionField.getValue() != null && descriptionField.getValue().length() > ChannelSettings.DESCRIPTION_MAX_LENGTH) {
            Notification.show("Description cannot exceed " + ChannelSettings.DESCRIPTION_MAX_LENGTH + " characters", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            descriptionField.focus();
            return;
        }

        // Check for duplicate name when creating new channel
        if (currentChannel == null && channelSettingsService.existsByName(nameField.getValue())) {
            Notification.show("Channel with name '" + nameField.getValue() + "' already exists", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            nameField.focus();
            return;
        }

        // Check for duplicate name when renaming existing channel
        if (currentChannel != null && !currentChannel.getName().equals(nameField.getValue())
                && channelSettingsService.existsByName(nameField.getValue())) {
            Notification.show("Channel with name '" + nameField.getValue() + "' already exists", 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            nameField.focus();
            return;
        }

        Map<String,String> properties;
        try { properties=collectProperties(); }
        catch (IllegalArgumentException invalid) {
            Notification.show(invalid.getMessage(),4000,Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        try {
            if (currentChannel == null) {
                channelSettingsService.createChannel(
                        nameField.getValue(),
                        typeField.getValue(),
                        directionField.getValue(),
                        descriptionField.getValue().isBlank() ? null : descriptionField.getValue(),
                        properties, enabledField.getValue()
                );
                Notification.show("Channel created", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } else {
                channelSettingsService.updateChannel(
                        channelId,
                        nameField.getValue(),
                        descriptionField.getValue().isBlank() ? null : descriptionField.getValue(),
                        enabledField.getValue(),
                        properties
                );
                Notification.show("Channel updated", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }
            close();
            if (onSaveCallback != null) {
                onSaveCallback.accept(null);
            }
        } catch (Exception e) {
            Notification.show("Error saving channel: " + e.getMessage(), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
}
