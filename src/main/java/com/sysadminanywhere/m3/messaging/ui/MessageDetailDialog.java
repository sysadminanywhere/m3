package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageMetadata;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.tabs.TabSheet;
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
    private final com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService submissions;
    private Message currentMessage;
    private final Grid<MessageService.JobInfo> jobsGrid = new Grid<>();
    private final Button retryButton = new Button(t("Retry processing"));
    private final Button sourceMessageButton = new Button(t("View original message"));
    private Consumer<Long> onChangeCallback;

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
    private final ComboBox<String> charsetSelector = new ComboBox<>(t("Preview charset"));
    private final Span previewHint = new Span();
    private final Anchor downloadLink = new Anchor();
    private final Map<String, String> metadataValues = new TreeMap<>();
    private byte[] originalBytes;

    public MessageDetailDialog(MessageService messageService, MessageMetadataRepository metadataRepository,
            com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService submissions) {
        this.messageService = messageService;
        this.metadataRepository = metadataRepository;
        this.submissions = submissions;

        addClassName("message-detail-dialog");
        setHeaderTitle(t("Message Details"));
        setWidth("1000px");
        setMaxWidth("calc(100vw - 32px)");
        setHeight("85vh");

        idField = createReadOnlyField("ID");
        statusField = createReadOnlyField(t("Status"));
        directionField = createReadOnlyField(t("Direction"));
        sourceSystemField = createReadOnlyField(t("Source System"));
        targetSystemField = createReadOnlyField(t("Target System"));
        payloadTypeField = createReadOnlyField(t("Payload Type"));
        createdAtField = createReadOnlyField(t("Created At"));
        processedAtField = createReadOnlyField(t("Processed At"));
        charsetField = createReadOnlyField(t("Stored charset / origin"));
        sizeField = createReadOnlyField(t("Payload bytes"));

        payloadArea = new TextArea(t("Payload"));
        payloadArea.setWidthFull();
        payloadArea.setHeight("calc(85vh - 260px)");
        payloadArea.setReadOnly(true);
        charsetSelector.setItems("UTF-8", "windows-1251", "KOI8-R", "IBM866", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE");
        charsetSelector.addValueChangeListener(event -> refreshPreview());
        previewHint.getStyle().set("white-space", "normal");

        metadataGrid = new Grid<>();
        metadataGrid.addColumn(MessageMetadata::getKey).setHeader(t("Key")).setAutoWidth(true).setFlexGrow(0);
        metadataGrid.addComponentColumn(item -> {
            var value = new Span(item.getValue());
            value.getElement().setAttribute("title", item.getValue());
            return value;
        }).setHeader(t("Value")).setFlexGrow(1);
        metadataGrid.setEmptyStateText(t("No metadata"));
        metadataGrid.setWidthFull();
        metadataGrid.setHeightFull();

        jobsGrid.addColumn(MessageService.JobInfo::ruleId).setHeader(t("Rule"));
        jobsGrid.addColumn(MessageService.JobInfo::pool).setHeader(t("Worker pool"));
        jobsGrid.addColumn(job -> Translations.enumLabel(job.status())).setHeader(t("Status"));
        jobsGrid.addColumn(MessageService.JobInfo::attempts).setHeader(t("Attempts"));
        jobsGrid.addComponentColumn(job -> {
            var error = new Span(job.error() == null ? "" : job.error());
            if (job.error() != null) error.getElement().setAttribute("title", job.error());
            return error;
        }).setHeader(t("Error")).setFlexGrow(2);
        jobsGrid.setHeightFull(); jobsGrid.setWidthFull();
        retryButton.addClickListener(event -> {
            try {
                Long id = currentMessage.getId(); messageService.retry(id);
                openMessage(id);
                if (onChangeCallback != null) onChangeCallback.accept(id);
            } catch (IllegalArgumentException invalid) {
                com.vaadin.flow.component.notification.Notification.show(t(invalid.getMessage()),5000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            }
        });
        FormLayout detailsTab = new FormLayout(idField, statusField, directionField,
                sourceSystemField, targetSystemField, payloadTypeField,
                createdAtField, processedAtField, charsetField, sizeField);
        detailsTab.addClassName("message-summary-form");
        detailsTab.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("600px", 2), new FormLayout.ResponsiveStep("1150px", 3));
        var detailsContent = new VerticalLayout(detailsTab);
        detailsContent.setPadding(false); detailsContent.setWidthFull();
        var metadataContent = new VerticalLayout(metadataGrid);
        metadataContent.setPadding(false); metadataContent.setSizeFull();
        var processingContent = new VerticalLayout(jobsGrid);
        processingContent.setPadding(false); processingContent.setSizeFull();
        var payloadContent = new VerticalLayout(charsetSelector, previewHint, payloadArea);
        payloadContent.setPadding(false); payloadContent.setSizeFull();
        var tabs = new TabSheet();
        tabs.addClassName("message-detail-tabs");
        tabs.setWidthFull();
        tabs.setHeight("calc(85vh - 120px)");
        tabs.add(t("Overview"), detailsContent);
        tabs.add(t("Message body"), payloadContent);
        tabs.add(t("Metadata"), metadataContent);
        tabs.add(t("Processing"), processingContent);
        add(tabs);

        // Footer buttons
        downloadLink.setText(t("Download original payload"));
        downloadLink.getElement().setAttribute("download", true);

        var deleteButton = new Button(VaadinIcon.TRASH.create(), event -> deleteMessage());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.setTooltipText(t("Delete message"));

        var closeButton = new Button(t("Close"), event -> close());

        sourceMessageButton.setVisible(false);
        sourceMessageButton.addClickListener(event -> openMessage(Long.parseLong(metadataValues.get("sourceMessageId"))));
        getFooter().add(downloadLink, sourceMessageButton, new Button(t("Forward to channel"), event -> openForwardDialog()), retryButton, new Button(t("Refresh"), event -> {
            if (currentMessage != null) openMessage(currentMessage.getId());
        }), deleteButton, closeButton);
    }

    private void openForwardDialog() {
        if (currentMessage == null) return;
        long sourceId = currentMessage.getId();
        var choices = submissions.forwardingRules();
        var channels = new java.util.LinkedHashMap<Long, String>();
        choices.forEach(choice -> channels.put(choice.channelId(), choice.channelName()));
        var dialog = new Dialog();
        dialog.setHeaderTitle(t("Forward message #") + sourceId);
        dialog.setWidth("520px"); dialog.setMaxWidth("calc(100vw - 32px)");
        var channel = new ComboBox<Long>(t("Destination channel"));
        channel.setItems(channels.keySet()); channel.setItemLabelGenerator(channels::get);
        channel.setWidthFull(); channel.setRequiredIndicatorVisible(true);
        var rule = new ComboBox<com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService.ForwardingRule>(t("Outbound rule"));
        rule.setItemLabelGenerator(choice -> choice.ruleName() + " (#" + choice.ruleId() + ")");
        rule.setWidthFull(); rule.setRequiredIndicatorVisible(true); rule.setEnabled(false);
        var send = new Button(t("Create copy and send"));
        send.addThemeVariants(ButtonVariant.PRIMARY); send.setEnabled(false);
        channel.addValueChangeListener(event -> {
            rule.clear();
            var matching = choices.stream().filter(choice -> java.util.Objects.equals(choice.channelId(), event.getValue())).toList();
            rule.setItems(matching); rule.setEnabled(!matching.isEmpty());
            if (matching.size() == 1) rule.setValue(matching.getFirst());
        });
        rule.addValueChangeListener(event -> send.setEnabled(event.getValue() != null));
        String requestKey = "ui-forward:" + java.util.UUID.randomUUID();
        send.setDisableOnClick(true);
        send.addClickListener(event -> {
            if (rule.getValue() == null) { send.setEnabled(false); return; }
            try {
                var copy = submissions.forward(sourceId, rule.getValue().ruleId(), requestKey);
                dialog.close();
                if (onChangeCallback != null) onChangeCallback.accept(copy.getId());
                openMessage(copy.getId());
                com.vaadin.flow.component.notification.Notification.show(t("Copy #") + copy.getId()
                        + t(" queued for delivery. Original #") + sourceId + t(" retained."), 5000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            } catch (org.springframework.web.server.ResponseStatusException error) {
                send.setEnabled(true);
                com.vaadin.flow.component.notification.Notification.show(t(error.getReason()), 5000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            } catch (IllegalArgumentException error) {
                send.setEnabled(true);
                com.vaadin.flow.component.notification.Notification.show(t(error.getMessage()), 5000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            }
        });
        var hint = new Span(choices.isEmpty()
                ? t("Create an enabled outbound rule with a destination channel and worker pool first.")
                : t("Original bytes and metadata remain in the database. The selected rule processes and delivers a separate copy."));
        dialog.add(new VerticalLayout(hint, channel, rule));
        dialog.getFooter().add(new Button(t("Cancel"), event -> dialog.close()), send);
        dialog.open();
    }

    private TextField createReadOnlyField(String label) {
        var field = new TextField(t(label));
        field.setReadOnly(true);
        field.setWidthFull();
        return field;
    }

    public void setOnMessageChanged(Consumer<Long> callback) {
        this.onChangeCallback = callback;
    }

    public void openMessage(Long messageId) {
        currentMessage = messageService.findById(messageId);
        if (currentMessage != null) {
            setHeaderTitle(t("Message #") + messageId);
            var jobs = messageService.executionJobs(messageId);
            jobsGrid.setItems(jobs);
            retryButton.setVisible(currentMessage.getStatus() == com.sysadminanywhere.m3.messaging.domain.MessageStatus.FAILED);
            retryButton.setEnabled(jobs.stream().anyMatch(job -> job.status() == com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.FAILED));
            var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Translations.locale())
                    .withZone(ZoneId.systemDefault());

            idField.setValue(String.valueOf(currentMessage.getId()));
            statusField.setValue(Translations.enumLabel(currentMessage.getStatus()));
            directionField.setValue(Translations.enumLabel(currentMessage.getDirection()));
            sourceSystemField.setValue(currentMessage.getSourceSystem() != null ? currentMessage.getSourceSystem() : "");
            targetSystemField.setValue(currentMessage.getTargetSystem() != null ? currentMessage.getTargetSystem() : "");
            payloadTypeField.setValue(currentMessage.getPayloadType());
            createdAtField.setValue(dateTimeFormatter.format(currentMessage.getCreatedAt()));
            processedAtField.setValue(currentMessage.getProcessedAt() != null
                    ? dateTimeFormatter.format(currentMessage.getProcessedAt())
                    : "");
            charsetField.setValue((currentMessage.getCharset() == null ? t("Unknown") : currentMessage.getCharset())
                    + " / " + t(currentMessage.getCharsetSource()));
            sizeField.setValue(Integer.toString(currentMessage.getPayloadSize()));

            List<MessageMetadata> metadata = metadataRepository.findByMessageId(messageId);
            metadataGrid.setItems(metadata.stream().sorted(java.util.Comparator.comparing(MessageMetadata::getKey)).toList());
            metadataValues.clear();
            metadata.forEach(value -> metadataValues.put(value.getKey(), value.getValue()));
            String sourceId = metadataValues.get("sourceMessageId");
            boolean hasSource = false;
            try { hasSource = sourceId != null && Long.parseLong(sourceId) > 0 && Long.parseLong(sourceId) != messageId; }
            catch (NumberFormatException ignored) { }
            sourceMessageButton.setVisible(hasSource);
            sourceMessageButton.setText(t("View original #") + sourceId);
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
        } else {
            com.vaadin.flow.component.notification.Notification.show(t("Message #") + messageId + t(" no longer exists"), 5000,
                    com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            close();
        }
    }

    private void refreshPreview() {
        if (currentMessage == null) return;
        String text;
        {
            if (originalBytes == null || charsetSelector.getValue() == null) {
                payloadArea.clear();
                previewHint.setText(t("Choose a charset for text preview."));
                return;
            }
            try {
                text = Charset.forName(charsetSelector.getValue()).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(originalBytes)).toString();
                previewHint.setText((currentMessage.getCharset() == null ? t("Stored charset is unknown; this is a manual preview. ") : "")
                        + t("Changing preview charset does not change stored bytes. Download preserves the original bytes."));
            } catch (java.nio.charset.CharacterCodingException invalid) {
                payloadArea.clear();
                previewHint.setText(t("Cannot decode with the selected charset. Select another charset or download the original bytes."));
                return;
            }
        }
        payloadArea.setValue(text);
    }

    private void deleteMessage() {
        if (currentMessage == null || currentMessage.getId() == null) {
            return;
        }

        var confirmDialog = new Dialog();
        confirmDialog.setHeaderTitle(t("Delete Message"));
        confirmDialog.add(new com.vaadin.flow.component.html.Span(t("Are you sure you want to delete this message?")));

        var confirmButton = new Button(t("Delete"), e -> {
            Long deletedId = currentMessage.getId();
            try { messageService.deleteMessage(deletedId); }
            catch (IllegalArgumentException invalid) {
                com.vaadin.flow.component.notification.Notification.show(t(invalid.getMessage()),5000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
                return;
            }
            confirmDialog.close();
            close();
            if (onChangeCallback != null) {
                onChangeCallback.accept(deletedId);
            }
        });
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        var cancelButton = new Button(t("Cancel"), e -> confirmDialog.close());

        confirmDialog.getFooter().add(cancelButton, confirmButton);
        confirmDialog.open();
    }
}
