package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
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
import com.vaadin.flow.router.*;

import java.util.HashMap;
import java.util.Map;

@Route(value = "channel/:channelId?")
class ChannelDetailView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private final ChannelSettingsService channelSettingsService;
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
    private final Map<String, ComboBox<String>> charsetFields = new HashMap<>();
    private final Map<String, PasswordField> passwordFields = new HashMap<>();
    private final Map<String, NumberField> numberFields = new HashMap<>();
    private final Map<String, Checkbox> checkboxes = new HashMap<>();

    private final VerticalLayout connectionTabContent;
    private final VerticalLayout advancedTabContent;

    ChannelDetailView(ChannelSettingsService channelSettingsService) {
        this.channelSettingsService = channelSettingsService;

        nameField = new TextField(t("Name"));
        nameField.setRequired(true);
        nameField.setWidthFull();

        typeField = new ComboBox<>(t("Type"));
        typeField.setItems(ChannelType.values()); typeField.setItemLabelGenerator(Translations::enumLabel);
        typeField.setRequired(true);
        typeField.addValueChangeListener(event -> onTypeChanged(event.getValue()));

        directionField = new ComboBox<>(t("Direction"));
        directionField.setItems(ChannelDirection.values()); directionField.setItemLabelGenerator(Translations::enumLabel);
        directionField.setValue(ChannelDirection.INBOUND);
        directionField.setRequired(true);

        descriptionField = new TextArea(t("Description"));
        descriptionField.setWidthFull();

        enabledField = new Checkbox(t("Outbound channel available"));
        enabledField.setValue(true);
        enabledField.setVisible(directionField.getValue()==ChannelDirection.OUTBOUND);
        directionField.addValueChangeListener(event -> {
            enabledField.setVisible(event.getValue()==ChannelDirection.OUTBOUND);
            updateRequiredMarkers();
        });

        var basicForm = new FormLayout();
        basicForm.add(nameField, typeField, directionField, enabledField);
        basicForm.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("760px", 2));
        basicForm.setWidthFull();

        connectionTabContent = new VerticalLayout();
        connectionTabContent.setSpacing(true);
        connectionTabContent.setPadding(false);
        connectionTabContent.addClassName("channel-properties");

        advancedTabContent = new VerticalLayout();
        advancedTabContent.setSpacing(true);
        advancedTabContent.setPadding(false);
        advancedTabContent.addClassName("channel-properties");

        var connectionTab = new Tab(t("Connection"));
        var advancedTab = new Tab(t("Advanced"));
        propertyTabs = new Tabs(connectionTab, advancedTab);
        propertyTabs.addSelectedChangeListener(event -> updateTabContent());

        var tabContentWrapper = new VerticalLayout();
        tabContentWrapper.add(propertyTabs);
        tabContentWrapper.setPadding(false);
        tabContentWrapper.setSpacing(false);

        var saveButton = new Button(t("Save"), event -> saveChannel());
        saveButton.addThemeVariants(ButtonVariant.PRIMARY);

        var backButton = new Button(t("Back to Channels"), event -> getUI().ifPresent(ui -> ui.navigate(ChannelListView.class)));

        var toolbar = new HorizontalLayout(saveButton, backButton);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        setSizeFull();
        add(toolbar, basicForm, descriptionField, tabContentWrapper, connectionTabContent);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var channelIdParam = event.getRouteParameters().get("channelId");
        if (channelIdParam.isPresent()) {
            try {
                this.channelId = Long.parseLong(channelIdParam.get());
                loadChannel();
            } catch (NumberFormatException e) {
                this.channelId = null;
                currentChannel = null;
                clearForm();
            }
        } else {
            this.channelId = null;
            currentChannel = null;
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

    private void loadChannel() {
        currentChannel = channelSettingsService.findById(channelId).orElse(null);
        if (currentChannel != null) {
            nameField.setValue(currentChannel.getName());
            typeField.setValue(currentChannel.getChannelType());
            directionField.setValue(currentChannel.getDirection());
            descriptionField.setValue(currentChannel.getDescription() != null ? currentChannel.getDescription() : "");
            enabledField.setValue(currentChannel.getEnabled());

            loadPropertyFields(currentChannel.getProperties());
            typeField.setEnabled(false);
            directionField.setEnabled(false);
        }
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
        charsetFields.clear();
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
                createCharsetField("charset", t("Source charset (blank = unknown)"), properties.getOrDefault("charset", "")),
                createCharsetField("outputCharset", t("Transformed output charset (optional)"), properties.getOrDefault("outputCharset", "")));
        var visible=new java.util.HashSet<>(textFields.keySet());
        visible.addAll(charsetFields.keySet());
        visible.addAll(passwordFields.keySet()); visible.addAll(numberFields.keySet()); visible.addAll(checkboxes.keySet());
        extraSettings.load(properties,visible); advancedTabContent.add(extraSettings);
        updateRequiredMarkers();
        updateTabContent();
    }

    private void setupFtpFields(Map<String, String> properties) {
        var hostField = createTextField("host", t("Host"), properties.getOrDefault("host", ""));
        var portField = createNumberField("port", t("Port"), Double.parseDouble(properties.getOrDefault("port", "21")));
        var usernameField = createTextField("username", t("Username"), properties.getOrDefault("username", ""));
        var passwordField = createPasswordField("password", t("Password"), properties.getOrDefault("password", ""));
        var remoteDirField = createTextField("remoteDirectory", t("Remote Directory"), properties.getOrDefault("remoteDirectory", "/"));
        var filePatternField = createTextField("filePattern", t("File Pattern"), properties.getOrDefault("filePattern", "*"));

        connectionTabContent.add(hostField, portField, usernameField, passwordField, remoteDirField, filePatternField);

        var passiveModeField = createCheckbox("passiveMode", t("Passive Mode"), Boolean.parseBoolean(properties.getOrDefault("passiveMode", "true")));
        var deleteRemoteField = createCheckbox("deleteRemoteFiles", t("Delete Remote Files"), Boolean.parseBoolean(properties.getOrDefault("deleteRemoteFiles", "false")));

        advancedTabContent.add(passiveModeField, deleteRemoteField,
                createNumberField("pollingInterval", t("Polling Interval (ms)"), Double.parseDouble(properties.getOrDefault("pollingInterval", "5000"))),
                createNumberField("minFileAgeMs", t("Minimum File Age (ms)"), Double.parseDouble(properties.getOrDefault("minFileAgeMs", "1000"))));
    }

    private void setupSftpFields(Map<String, String> properties) {
        var hostField = createTextField("host", t("Host"), properties.getOrDefault("host", ""));
        var portField = createNumberField("port", t("Port"), Double.parseDouble(properties.getOrDefault("port", "22")));
        var usernameField = createTextField("username", t("Username"), properties.getOrDefault("username", ""));
        var passwordField = createPasswordField("password", t("Password"), properties.getOrDefault("password", ""));
        var privateKeyField = createTextField("privateKey", t("Private Key Path"), properties.getOrDefault("privateKey", ""));
        var remoteDirField = createTextField("remoteDirectory", t("Remote Directory"), properties.getOrDefault("remoteDirectory", "/"));
        var filePatternField = createTextField("filePattern", t("File Pattern"), properties.getOrDefault("filePattern", "*"));

        connectionTabContent.add(hostField, portField, usernameField, passwordField, privateKeyField, remoteDirField, filePatternField);

        var deleteRemoteField = createCheckbox("deleteRemoteFiles", t("Delete Remote Files"), Boolean.parseBoolean(properties.getOrDefault("deleteRemoteFiles", "false")));

        advancedTabContent.add(deleteRemoteField,
                createTextField("knownHostsPath", t("Known Hosts File"), properties.getOrDefault("knownHostsPath", "")),
                createCheckbox("allowUnknownKeys", t("Allow Unknown Host Keys"), Boolean.parseBoolean(properties.getOrDefault("allowUnknownKeys", "false"))),
                createPasswordField("privateKeyPassphrase", t("Private Key Passphrase"), properties.getOrDefault("privateKeyPassphrase", "")),
                createNumberField("pollingInterval", t("Polling Interval (ms)"), Double.parseDouble(properties.getOrDefault("pollingInterval", "5000"))),
                createNumberField("minFileAgeMs", t("Minimum File Age (ms)"), Double.parseDouble(properties.getOrDefault("minFileAgeMs", "1000"))));
    }

    private void setupKafkaFields(Map<String, String> properties) {
        var bootstrapField = createTextField("bootstrapServers", t("Bootstrap Servers"), properties.getOrDefault("bootstrapServers", "localhost:9092"));
        var topicField = createTextField("topic", t("Topic"), properties.getOrDefault("topic", ""));
        var groupIdField = createTextField("groupId", t("Group ID"), properties.getOrDefault("groupId", "m3-consumer"));

        connectionTabContent.add(bootstrapField, topicField, groupIdField);

        var autoOffsetField = createTextField("autoOffsetReset", t("Auto Offset Reset"), properties.getOrDefault("autoOffsetReset", "earliest"));
        var keyDeserializerField = createTextField("keyDeserializer", t("Key Deserializer"), properties.getOrDefault("keyDeserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
        var valueDeserializerField = createTextField("valueDeserializer", t("Value Deserializer"), properties.getOrDefault("valueDeserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"));

        advancedTabContent.add(autoOffsetField, keyDeserializerField, valueDeserializerField,
                createTextField("kafka.security.protocol", t("Security Protocol"), properties.getOrDefault("kafka.security.protocol", "PLAINTEXT")),
                createTextField("kafka.sasl.mechanism", t("SASL Mechanism"), properties.getOrDefault("kafka.sasl.mechanism", "")),
                createPasswordField("kafka.sasl.jaas.config", t("SASL JAAS Configuration"), properties.getOrDefault("kafka.sasl.jaas.config", "")),
                createTextField("kafka.ssl.truststore.location", t("SSL Truststore Path"), properties.getOrDefault("kafka.ssl.truststore.location", "")),
                createPasswordField("kafka.ssl.truststore.password", t("SSL Truststore Password"), properties.getOrDefault("kafka.ssl.truststore.password", "")));
    }

    private void setupDirectoryFields(Map<String, String> properties) {
        var directoryPathField = createTextField("directoryPath", t("Directory Path"), properties.getOrDefault("directoryPath", ""));
        var filePatternField = createTextField("filePattern", t("File Pattern"), properties.getOrDefault("filePattern", "*"));

        connectionTabContent.add(directoryPathField, filePatternField);

        var pollingIntervalField = createNumberField("pollingInterval", t("Polling Interval (ms)"), Double.parseDouble(properties.getOrDefault("pollingInterval", "5000")));
        var deleteAfterProcessingField = createCheckbox("deleteAfterProcessing", t("Delete After Processing"), Boolean.parseBoolean(properties.getOrDefault("deleteAfterProcessing", "false")));
        var recursiveField = createCheckbox("recursive", t("Recursive Scan"), Boolean.parseBoolean(properties.getOrDefault("recursive", "false")));

        advancedTabContent.add(pollingIntervalField, deleteAfterProcessingField, recursiveField,
                createNumberField("minFileAgeMs", t("Minimum File Age (ms)"), Double.parseDouble(properties.getOrDefault("minFileAgeMs", "1000"))));
    }

    private void setupRabbitMqFields(Map<String, String> properties) {
        var hostField = createTextField("host", t("Host"), properties.getOrDefault("host", "localhost"));
        var portField = createNumberField("port", t("Port"), Double.parseDouble(properties.getOrDefault("port", "5672")));
        var usernameField = createTextField("username", t("Username"), properties.getOrDefault("username", "guest"));
        var passwordField = createPasswordField("password", t("Password"), properties.getOrDefault("password", "guest"));
        var virtualHostField = createTextField("virtualHost", t("Virtual Host"), properties.getOrDefault("virtualHost", "/"));

        connectionTabContent.add(hostField, portField, usernameField, passwordField, virtualHostField);

        var queueField = createTextField("queue", t("Queue Name"), properties.getOrDefault("queue", ""));
        var exchangeField = createTextField("exchange", t("Exchange Name"), properties.getOrDefault("exchange", ""));
        exchangeField.addValueChangeListener(event -> updateRequiredMarkers());
        var routingKeyField = createTextField("routingKey", t("Routing Key"), properties.getOrDefault("routingKey", ""));

        var declareQueue = createCheckbox("declareQueue", t("Declare Durable Queue and Binding"), Boolean.parseBoolean(properties.getOrDefault("declareQueue", "false")));
        declareQueue.addValueChangeListener(event -> updateRequiredMarkers());
        advancedTabContent.add(queueField, exchangeField, routingKeyField, declareQueue);
    }

    private TextField createTextField(String key, String label, String value) {
        var field = new TextField(t(label));
        field.setValue(value);
        field.setWidthFull();
        textFields.put(key, field);
        return field;
    }

    private ComboBox<String> createCharsetField(String key, String label, String value) {
        var field = new ComboBox<String>(label);
        field.setItems("UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE", "ISO-8859-1", "windows-1251",
                "windows-1252", "CP866", "US-ASCII", "KOI8-R", "Shift_JIS", "GB18030");
        field.setAllowCustomValue(true);
        field.addCustomValueSetListener(event -> field.setValue(event.getDetail()));
        field.setClearButtonVisible(true);
        field.setValue(value == null || value.isBlank() ? null : value);
        field.setWidthFull();
        field.setHelperText(t("Choose a common charset or enter any Java-supported charset name"));
        charsetFields.put(key, field);
        return field;
    }

    private PasswordField createPasswordField(String key, String label, String value) {
        var field = new PasswordField(t(label));
        field.setValue(value);
        field.setWidthFull();
        passwordFields.put(key, field);
        return field;
    }

    private NumberField createNumberField(String key, String label, Double value) {
        var field = new NumberField(t(label));
        field.setValue(value);
        field.setWidthFull();
        field.setRequired(true);
        numberFields.put(key, field);
        return field;
    }

    private Checkbox createCheckbox(String key, String label, boolean value) {
        var field = new Checkbox(t(label));
        field.setValue(value);
        checkboxes.put(key, field);
        return field;
    }

    private void updateRequiredMarkers() {
        ChannelType type = typeField.getValue();
        ChannelDirection direction = directionField.getValue();
        textFields.forEach((key, field) -> field.setRequired(isRequiredProperty(type, direction, key)));
    }

    private boolean isRequiredProperty(ChannelType type, ChannelDirection direction, String key) {
        if (type == null) return false;
        return switch (type) {
            case DIRECTORY -> key.equals("directoryPath");
            case FTP -> key.equals("host") || key.equals("username") || key.equals("remoteDirectory");
            case SFTP -> key.equals("host") || key.equals("username") || key.equals("remoteDirectory")
                    || (key.equals("knownHostsPath") && !Boolean.TRUE.equals(checkboxes.containsKey("allowUnknownKeys")
                    && checkboxes.get("allowUnknownKeys").getValue()));
            case KAFKA -> key.equals("bootstrapServers") || key.equals("topic");
            case RABBITMQ -> key.equals("host") || key.equals("username")
                    || (key.equals("queue") && (direction == ChannelDirection.INBOUND
                    || (textFields.containsKey("exchange") && textFields.get("exchange").getValue().isBlank())
                    || (checkboxes.containsKey("declareQueue") && checkboxes.get("declareQueue").getValue())))
                    || (key.equals("routingKey") && direction == ChannelDirection.OUTBOUND
                    && textFields.containsKey("exchange") && !textFields.get("exchange").getValue().isBlank());
        };
    }

    private void updateTabContent() {
        var selectedTab = propertyTabs.getSelectedTab();
        var tabIndex = propertyTabs.getChildren().toList().indexOf(selectedTab);

        removeIfExists(connectionTabContent);
        removeIfExists(advancedTabContent);

        if (tabIndex == 0) {
            add(connectionTabContent);
        } else if (tabIndex == 1) {
            add(advancedTabContent);
        }
    }

    private void removeIfExists(VerticalLayout content) {
        if (getChildren().anyMatch(c -> c == content)) {
            remove(content);
        }
    }

    private Map<String, String> collectProperties() {
        Map<String, String> properties = new HashMap<>(extraSettings.settings());

        textFields.forEach((key, field) -> properties.put(key, field.getValue()));
        charsetFields.forEach((key, field) -> properties.put(key, field.getValue() == null ? "" : field.getValue()));
        passwordFields.forEach((key, field) -> properties.put(key, field.getValue()));
        numberFields.forEach((key,field) -> {
            Double value=field.getValue();
            if (value==null || value<1 || value>65535 || value!=Math.rint(value))
                throw new IllegalArgumentException(key+t(" must be an integer between 1 and 65535"));
            properties.put(key,Integer.toString(value.intValue()));
        });
        checkboxes.forEach((key, field) -> properties.put(key, String.valueOf(field.getValue())));

        return properties;
    }

    private void saveChannel() {
        if (nameField.getValue().isBlank() || typeField.getValue() == null || directionField.getValue() == null) {
            Notification.show(t("Please fill all required fields"), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        Map<String,String> properties;
        try { properties=collectProperties(); }
        catch (IllegalArgumentException invalid) {
            Notification.show(t(invalid.getMessage()),4000,Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR);
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
                Notification.show(t("Channel created"), 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } else {
                channelSettingsService.updateChannel(
                        channelId,
                        nameField.getValue(),
                        descriptionField.getValue().isBlank() ? null : descriptionField.getValue(),
                        enabledField.getValue(),
                        properties
                );
                Notification.show(t("Channel updated"), 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }
            getUI().ifPresent(ui -> ui.navigate(ChannelListView.class));
        } catch (Exception e) {
            Notification.show(t("Error saving channel: ") + t(e.getMessage()), 3000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
    @Override public String getPageTitle() { return t("Channel Settings"); }
}
