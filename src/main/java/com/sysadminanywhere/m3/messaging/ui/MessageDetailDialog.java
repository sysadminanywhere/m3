package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageMetadata;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final TextField charsetField;
    private final TextField sizeField;
    private final TextArea payloadArea;
    private final Grid<MessageMetadata> metadataGrid;
    private final ComboBox<String> charsetSelector = new ComboBox<>("Preview charset");
    private final Span previewHint = new Span();
    private final Anchor downloadLink = new Anchor();
    private final Map<String, String> metadataValues = new TreeMap<>();
    private byte[] originalBytes;

    public MessageDetailDialog(MessageService messageService, MessageMetadataRepository metadataRepository) {
        this.messageService = messageService;
        this.metadataRepository = metadataRepository;

        setHeaderTitle("Message Details");
        setWidth("1000px");
        setMaxWidth("calc(100vw - 32px)");
        setHeight("85vh");

        idField = createReadOnlyField("ID");
        statusField = createReadOnlyField("Status");
        directionField = createReadOnlyField("Direction");
        sourceSystemField = createReadOnlyField("Source System");
        targetSystemField = createReadOnlyField("Target System");
        payloadTypeField = createReadOnlyField("Payload Type");
        createdAtField = createReadOnlyField("Created At");
        processedAtField = createReadOnlyField("Processed At");
        charsetField = createReadOnlyField("Stored charset / origin");
        sizeField = createReadOnlyField("Payload bytes");

        payloadArea = new TextArea("Payload");
        payloadArea.setWidthFull();
        payloadArea.setHeight("300px");
        payloadArea.setReadOnly(true);
        charsetSelector.setItems("UTF-8", "windows-1251", "KOI8-R", "IBM866", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE");
        charsetSelector.addValueChangeListener(event -> refreshPreview());
        previewHint.getStyle().set("white-space", "normal");

        metadataGrid = new Grid<>();
        metadataGrid.addColumn(MessageMetadata::getKey).setHeader("Key").setWidth("220px").setFlexGrow(0);
        metadataGrid.addColumn(MessageMetadata::getValue).setHeader("Value").setFlexGrow(1);
        metadataGrid.addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT);
        metadataGrid.setEmptyStateText("No metadata");
        metadataGrid.setWidthFull();
        metadataGrid.setHeight("240px");

        var detailsTab = new VerticalLayout();
        detailsTab.setSpacing(true);
        detailsTab.add(
                new HorizontalLayout(idField, directionField, statusField),
                new HorizontalLayout(sourceSystemField, targetSystemField, payloadTypeField),
                new HorizontalLayout(createdAtField, processedAtField),
                new HorizontalLayout(charsetField, sizeField)
        );
        detailsTab.getChildren().forEach(row -> {
            if (row instanceof HorizontalLayout layout) { layout.setWrap(true); layout.setWidthFull(); }
        });
        detailsTab.setPadding(false);
        var mainLayout = new VerticalLayout(detailsTab, charsetSelector, previewHint, payloadArea,
                new Span("Metadata"), metadataGrid);
        mainLayout.setPadding(false);
        mainLayout.setWidthFull();
        mainLayout.setFlexShrink(0, payloadArea, metadataGrid);
        add(mainLayout);

        // Footer buttons
        downloadLink.setText("Download original payload");
        downloadLink.getElement().setAttribute("download", true);

        var deleteButton = new Button(VaadinIcon.TRASH.create(), event -> deleteMessage());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.setTooltipText("Delete message");

        var closeButton = new Button("Close", event -> close());

        getFooter().add(downloadLink, deleteButton, closeButton);
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
            setHeaderTitle("Message #" + messageId);
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
            charsetField.setValue((currentMessage.getCharset() == null ? "Unknown" : currentMessage.getCharset())
                    + " / " + currentMessage.getCharsetSource());
            sizeField.setValue(Integer.toString(currentMessage.getPayloadSize()));

            List<MessageMetadata> metadata = metadataRepository.findByMessageId(messageId);
            metadataGrid.setItems(metadata.stream().sorted(java.util.Comparator.comparing(MessageMetadata::getKey)).toList());
            metadataValues.clear();
            metadata.forEach(value -> metadataValues.put(value.getKey(), value.getValue()));
            originalBytes = currentMessage.getPayloadBytes();
            boolean binary = "BASE64".equals(currentMessage.getPayloadFormat());
            charsetSelector.setEnabled(true);
            String charset = currentMessage.getCharset() == null ? "UTF-8" : currentMessage.getCharset();
            try { charset = Charset.forName(charset).name(); }
            catch (IllegalArgumentException invalid) { charset = "UTF-8"; }
            var choices = new java.util.LinkedHashSet<>(List.of("UTF-8", "windows-1251", "KOI8-R", "IBM866", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE"));
            choices.add(charset);
            charsetSelector.setItems(choices);
            charsetSelector.setValue(charset);
            try {
                byte[] content = originalBytes;
                String name = metadataValues.getOrDefault("fileName", "payload" + (binary ? ".bin" : ".txt"));
                name = name.replaceAll("[\\\\/:\\p{Cntrl}]", "_");
                String downloadName = "message-" + messageId + "-" + name;
                downloadLink.setHref(DownloadHandler.fromInputStream(event -> new DownloadResponse(
                        new ByteArrayInputStream(content), downloadName, "application/octet-stream", content.length)));
                downloadLink.setVisible(true);
            } catch (IllegalArgumentException invalid) {
                downloadLink.setVisible(false);
            }
            refreshPreview();

            open();
        }
    }

    private void refreshPreview() {
        if (currentMessage == null) return;
        String text;
        {
            if (originalBytes == null || charsetSelector.getValue() == null) {
                payloadArea.clear();
                previewHint.setText("Choose a charset for text preview.");
                return;
            }
            try {
                text = Charset.forName(charsetSelector.getValue()).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(originalBytes)).toString();
                previewHint.setText((currentMessage.getCharset() == null ? "Stored charset is unknown; this is a manual preview. " : "")
                        + "Changing preview charset does not change stored bytes. Download preserves the original bytes.");
            } catch (java.nio.charset.CharacterCodingException invalid) {
                payloadArea.clear();
                previewHint.setText("Cannot decode with the selected charset. Select another charset or download the original bytes.");
                return;
            }
        }
        payloadArea.setValue(text.length() > 5000 ? text.substring(0, 5000) + "\n\n… [Preview limited to 5000 characters; download for full content]" : text);
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
