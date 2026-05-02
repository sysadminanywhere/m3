package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageMetadata;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.StreamResource;

import java.io.ByteArrayInputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

public class MessageDetailDialog extends Dialog {

    private final MessageService messageService;
    private final MessageMetadataRepository metadataRepository;
    private Message currentMessage;
    private Consumer<Long> onDeleteCallback;

    private final TextField idField;
    private final TextField statusField;
    private final TextField directionField;
    private final TextField sourceSystemField;
    private final TextField targetSystemField;
    private final TextField payloadTypeField;
    private final TextField createdAtField;
    private final TextField processedAtField;
    private final TextArea payloadArea;
    private final Grid<MessageMetadata> metadataGrid;

    public MessageDetailDialog(MessageService messageService, MessageMetadataRepository metadataRepository) {
        this.messageService = messageService;
        this.metadataRepository = metadataRepository;

        setHeaderTitle("Message Details");
        setWidth("800px");
        setHeight("600px");

        idField = createReadOnlyField("ID");
        statusField = createReadOnlyField("Status");
        directionField = createReadOnlyField("Direction");
        sourceSystemField = createReadOnlyField("Source System");
        targetSystemField = createReadOnlyField("Target System");
        payloadTypeField = createReadOnlyField("Payload Type");
        createdAtField = createReadOnlyField("Created At");
        processedAtField = createReadOnlyField("Processed At");

        payloadArea = new TextArea("Payload");
        payloadArea.setWidthFull();
        payloadArea.setHeight("300px");
        payloadArea.setReadOnly(true);

        metadataGrid = new Grid<>();
        metadataGrid.addColumn(MessageMetadata::getKey).setHeader("Key");
        metadataGrid.addColumn(MessageMetadata::getValue).setHeader("Value");
        metadataGrid.setEmptyStateText("No metadata");
        metadataGrid.setSizeFull();

        // Tabs
        var detailsTab = new VerticalLayout();
        detailsTab.setSpacing(true);
        detailsTab.add(
                new HorizontalLayout(idField, directionField, statusField),
                new HorizontalLayout(sourceSystemField, targetSystemField, payloadTypeField),
                new HorizontalLayout(createdAtField, processedAtField)
        );

        var payloadTab = new VerticalLayout(payloadArea);
        payloadTab.setSizeFull();

        var metadataTab = new VerticalLayout(metadataGrid);
        metadataTab.setSizeFull();

        var detailsTabComponent = new Tab("Details");
        var payloadTabComponent = new Tab("Payload");
        var metadataTabComponent = new Tab("Metadata");

        var tabs = new Tabs(detailsTabComponent, payloadTabComponent, metadataTabComponent);
        var content = new VerticalLayout();
        content.setSizeFull();
        content.add(detailsTab);

        tabs.addSelectedChangeListener(event -> {
            content.removeAll();
            if (event.getSelectedTab() == detailsTabComponent) {
                content.add(detailsTab);
            } else if (event.getSelectedTab() == payloadTabComponent) {
                content.add(payloadTab);
            } else if (event.getSelectedTab() == metadataTabComponent) {
                content.add(metadataTab);
            }
        });

        var mainLayout = new VerticalLayout(tabs, content);
        mainLayout.setSizeFull();
        add(mainLayout);

        // Footer buttons
        var downloadButton = new Button(VaadinIcon.DOWNLOAD.create(), event -> downloadPayload());
        downloadButton.setTooltipText("Download payload");

        var deleteButton = new Button(VaadinIcon.TRASH.create(), event -> deleteMessage());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.setTooltipText("Delete message");

        var closeButton = new Button("Close", event -> close());

        getFooter().add(downloadButton, deleteButton, closeButton);
    }

    private TextField createReadOnlyField(String label) {
        var field = new TextField(label);
        field.setReadOnly(true);
        field.setWidth("200px");
        return field;
    }

    public void setOnDeleteCallback(Consumer<Long> callback) {
        this.onDeleteCallback = callback;
    }

    public void openMessage(Long messageId) {
        currentMessage = messageService.findById(messageId);
        if (currentMessage != null) {
            var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault());

            idField.setValue(String.valueOf(currentMessage.getId()));
            statusField.setValue(currentMessage.getStatus().name());
            directionField.setValue(currentMessage.getDirection().name());
            sourceSystemField.setValue(currentMessage.getSourceSystem() != null ? currentMessage.getSourceSystem() : "");
            targetSystemField.setValue(currentMessage.getTargetSystem() != null ? currentMessage.getTargetSystem() : "");
            payloadTypeField.setValue(currentMessage.getPayloadType());
            createdAtField.setValue(dateTimeFormatter.format(currentMessage.getCreatedAt()));
            processedAtField.setValue(currentMessage.getProcessedAt() != null
                    ? dateTimeFormatter.format(currentMessage.getProcessedAt())
                    : "");

            // Show preview of payload (first 5000 chars)
            var payload = currentMessage.getPayload();
            if (payload != null && payload.length() > 5000) {
                payloadArea.setValue(payload.substring(0, 5000) + "\n\n... [truncated, use download for full content]");
            } else {
                payloadArea.setValue(payload != null ? payload : "");
            }

            // Load metadata
            List<MessageMetadata> metadata = metadataRepository.findByMessageId(messageId);
            metadataGrid.setItems(metadata);

            open();
        }
    }

    private void downloadPayload() {
        if (currentMessage == null || currentMessage.getPayload() == null) {
            return;
        }

        var payload = currentMessage.getPayload();
        var payloadType = currentMessage.getPayloadType();
        var baseFileName = "message-" + currentMessage.getId();

        final byte[] content;
        final String fileName;
        if ("file".equals(payloadType) || payload.startsWith("JVBERi") || payload.startsWith("/9j/") || payload.startsWith("iVBORw")) {
            // Base64 encoded file
            byte[] decodedContent;
            String finalFileName;
            try {
                decodedContent = Base64.getDecoder().decode(payload);
                finalFileName = baseFileName + ".bin";
            } catch (IllegalArgumentException e) {
                decodedContent = payload.getBytes();
                finalFileName = baseFileName + ".txt";
            }
            content = decodedContent;
            fileName = finalFileName;
        } else {
            content = payload.getBytes();
            fileName = baseFileName + ".txt";
        }

        var resource = new StreamResource(fileName, () -> new ByteArrayInputStream(content));
        var anchor = new Anchor(resource, "");
        anchor.getElement().setAttribute("download", true);
        add(anchor);
        anchor.getElement().callJsFunction("click");
        remove(anchor);
    }

    private void deleteMessage() {
        if (currentMessage == null || currentMessage.getId() == null) {
            return;
        }

        var confirmDialog = new Dialog();
        confirmDialog.setHeaderTitle("Delete Message");
        confirmDialog.add(new com.vaadin.flow.component.html.Span("Are you sure you want to delete this message?"));

        var confirmButton = new Button("Delete", e -> {
            Long deletedId = currentMessage.getId();
            messageService.deleteMessage(deletedId);
            confirmDialog.close();
            close();
            if (onDeleteCallback != null) {
                onDeleteCallback.accept(deletedId);
            }
        });
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        var cancelButton = new Button("Cancel", e -> confirmDialog.close());

        confirmDialog.getFooter().add(cancelButton, confirmButton);
        confirmDialog.open();
    }
}
