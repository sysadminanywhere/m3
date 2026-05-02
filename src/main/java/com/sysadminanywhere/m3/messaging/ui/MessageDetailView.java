package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.ViewTitle;
import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageMetadata;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;

import java.io.ByteArrayInputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Base64;
import java.util.List;

@Route(value = "messages/:messageId")
class MessageDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final MessageService messageService;
    private final MessageMetadataRepository metadataRepository;
    private Long messageId;
    private Message currentMessage;

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
    private final VerticalLayout detailsTab;
    private final VerticalLayout payloadTab;
    private final VerticalLayout metadataTab;

    MessageDetailView(MessageService messageService, MessageMetadataRepository metadataRepository) {
        this.messageService = messageService;
        this.metadataRepository = metadataRepository;

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
        payloadArea.setHeight("400px");
        payloadArea.setReadOnly(true);

        metadataGrid = new Grid<>();
        metadataGrid.addColumn(MessageMetadata::getKey).setHeader("Key");
        metadataGrid.addColumn(MessageMetadata::getValue).setHeader("Value");
        metadataGrid.setEmptyStateText("No metadata");
        metadataGrid.setSizeFull();

        // Tabs
        detailsTab = new VerticalLayout();
        detailsTab.setSpacing(true);
        detailsTab.add(
                new HorizontalLayout(idField, directionField, statusField),
                new HorizontalLayout(sourceSystemField, targetSystemField, payloadTypeField),
                new HorizontalLayout(createdAtField, processedAtField)
        );

        payloadTab = new VerticalLayout(payloadArea);
        payloadTab.setSizeFull();

        metadataTab = new VerticalLayout(metadataGrid);
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

        var backButton = new Button("Back", event -> {
            if (currentMessage != null) {
                if (currentMessage.getDirection().name().equals("INBOUND")) {
                    getUI().ifPresent(ui -> ui.navigate(InboundMessagesView.class));
                } else {
                    getUI().ifPresent(ui -> ui.navigate(OutboundMessagesView.class));
                }
            } else {
                getUI().ifPresent(ui -> ui.navigate(InboundMessagesView.class));
            }
        });

        var downloadButton = new Button(VaadinIcon.DOWNLOAD.create(), event -> downloadPayload());
        downloadButton.setTooltipText("Download payload");

        var deleteButton = new Button(VaadinIcon.TRASH.create(), event -> deleteMessage());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.setTooltipText("Delete message");

        var toolbar = new HorizontalLayout(new ViewTitle("Message Details"), backButton, downloadButton, deleteButton);
        toolbar.setWidthFull();
        toolbar.setAlignItems(Alignment.CENTER);

        setSizeFull();
        add(toolbar, tabs, content);
    }

    private TextField createReadOnlyField(String label) {
        var field = new TextField(label);
        field.setReadOnly(true);
        field.setWidth("250px");
        return field;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var routeParams = event.getRouteParameters();
        routeParams.get("messageId").ifPresent(id -> {
            this.messageId = Long.parseLong(id);
            loadMessage();
        });
    }

    private void loadMessage() {
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

        var confirmDialog = new com.vaadin.flow.component.dialog.Dialog();
        confirmDialog.setHeaderTitle("Delete Message");
        confirmDialog.add(new com.vaadin.flow.component.html.Span("Are you sure you want to delete this message?"));

        var confirmButton = new Button("Delete", e -> {
            messageService.deleteMessage(currentMessage.getId());
            confirmDialog.close();
            // Navigate back after deletion
            if (currentMessage.getDirection().name().equals("INBOUND")) {
                getUI().ifPresent(ui -> ui.navigate(InboundMessagesView.class));
            } else {
                getUI().ifPresent(ui -> ui.navigate(OutboundMessagesView.class));
            }
        });
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        var cancelButton = new Button("Cancel", e -> confirmDialog.close());

        confirmDialog.getFooter().add(cancelButton, confirmButton);
        confirmDialog.open();
    }
}
