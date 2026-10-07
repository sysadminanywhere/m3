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
    private final Grid<com.sysadminanywhere.m3.messaging.service.MessageHistoryService.Event> historyGrid=new Grid<>();
    private final VerticalLayout relatedMessages=new VerticalLayout();
    private final ComboBox<com.sysadminanywhere.m3.messaging.service.MessageInspectionService.BodyVersion> bodyVersion=new ComboBox<>(t("Payload version"));
    private final com.vaadin.flow.component.checkbox.Checkbox revealOriginal=new com.vaadin.flow.component.checkbox.Checkbox(t("Show original (access is audited)"));
    private boolean loading;
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
        bodyVersion.setItemLabelGenerator(version -> version.jobId()==null?t("Stored payload"):t("Prepared delivery #{0}",version.jobId()));
        bodyVersion.setWidthFull();
        charsetSelector.setWidth("220px");charsetSelector.setMaxWidth("100%");
        bodyVersion.addValueChangeListener(event -> refreshPreview());
        revealOriginal.setVisible(new com.sysadminanywhere.m3.base.security.ServiceAccess().original());
        revealOriginal.addValueChangeListener(event -> refreshPreview());
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
            boolean uncertain=messageService.executionJobs(currentMessage.getId()).stream().anyMatch(MessageService.JobInfo::uncertain);
            if(!uncertain) retry(false);
            else {
                var confirmation=new com.vaadin.flow.component.confirmdialog.ConfirmDialog();
                confirmation.setHeader(t("Uncertain delivery"));
                confirmation.setText(t("The receiver may already have this message. Verify the receiver before retrying; a duplicate is possible."));
                confirmation.setConfirmText(t("Receiver checked — retry")); confirmation.setCancelText(t("Cancel")); confirmation.setCancelable(true);
                confirmation.addConfirmListener(ignored -> retry(true)); confirmation.open();
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
        var controls=new HorizontalLayout(bodyVersion,charsetSelector);controls.setWidthFull();controls.setFlexGrow(1,bodyVersion);
        controls.getStyle().set("flex-wrap","wrap").set("align-items","end");
        var payloadContent = new VerticalLayout(controls,revealOriginal, previewHint, payloadArea);
        payloadContent.setPadding(false); payloadContent.setSizeFull();
        var tabs = new TabSheet();
        tabs.addClassName("message-detail-tabs");
        tabs.setWidthFull();
        tabs.setHeight("calc(85vh - 120px)");
        tabs.add(t("Overview"), detailsContent);
        tabs.add(t("Message body"), payloadContent);
        tabs.add(t("Metadata"), metadataContent);
        tabs.add(t("Processing"), processingContent);
        historyGrid.addColumn(row -> java.time.format.DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Translations.locale()).withZone(ZoneId.systemDefault()).format(row.occurredAt())).setHeader(t("Time"));
        historyGrid.addColumn(row -> t(row.kind())).setHeader(t("Event"));
        historyGrid.addColumn(row -> row.ruleId()==null?"":row.ruleId()+" / "+row.pool()).setHeader(t("Rule / pool"));
        historyGrid.addColumn(row -> row.attempt()==0?"":row.attempt()).setHeader(t("Attempt"));
        historyGrid.addColumn(com.sysadminanywhere.m3.messaging.service.MessageHistoryService.Event::status).setHeader(t("Status"));
        historyGrid.addColumn(com.sysadminanywhere.m3.messaging.service.MessageHistoryService.Event::actor).setHeader(t("Actor"));
        historyGrid.addColumn(com.sysadminanywhere.m3.messaging.service.MessageHistoryService.Event::detail).setHeader(t("Details"));
        historyGrid.setWidthFull(); historyGrid.setHeight("450px");
        tabs.add(t("Routing history"),new VerticalLayout(relatedMessages,historyGrid));
        tabs.setSelectedIndex(1);
        add(tabs);

        // Footer buttons
        downloadLink.setText(t("Download original payload"));
        downloadLink.getElement().setAttribute("download", true);

        boolean canOperate = new com.sysadminanywhere.m3.base.security.ServiceAccess().operate();
        var deleteButton = new Button(VaadinIcon.TRASH.create(), event -> deleteMessage());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        deleteButton.setVisible(canOperate);
        deleteButton.setTooltipText(t("Delete message"));

        var closeButton = new Button(t("Close"), event -> close());

        sourceMessageButton.setVisible(false);
        sourceMessageButton.addClickListener(event -> openMessage(Long.parseLong(metadataValues.get("sourceMessageId"))));
        var forwardButton = new Button(t("Forward to channel"), event -> openForwardDialog());
        forwardButton.setVisible(canOperate);
        getFooter().add(downloadLink, sourceMessageButton, forwardButton, retryButton, new Button(t("Refresh"), event -> {
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
        loading=true;
        revealOriginal.setValue(false);
        currentMessage = messageService.findById(messageId);
        if (currentMessage != null) {
            historyGrid.setItems(messageService.history().events(messageId));
            relatedMessages.removeAll(); relatedMessages.setPadding(false);
            relatedMessages.add(new Span(currentMessage.isArchived()?t("Cold archive · payload is loaded on request"):t("Hot storage")));
            for(var link:messageService.history().links(messageId)) relatedMessages.add(new Button(t("Message #{0}",link.id())+" · "+Translations.enumLabel(com.sysadminanywhere.m3.messaging.domain.MessageDirection.valueOf(link.direction()))+" · "+Translations.enumLabel(com.sysadminanywhere.m3.messaging.domain.MessageStatus.valueOf(link.status())),event -> openMessage(link.id())));
            var versions=messageService.inspection().versions(messageId); bodyVersion.setItems(versions);bodyVersion.setValue(versions.getFirst());
            setHeaderTitle(t("Message #") + messageId);
            var jobs = messageService.executionJobs(messageId);
            jobsGrid.setItems(jobs);
            retryButton.setVisible(new com.sysadminanywhere.m3.base.security.ServiceAccess().operate() && currentMessage.getStatus() == com.sysadminanywhere.m3.messaging.domain.MessageStatus.FAILED);
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
            metadataValues.clear();
            metadata.forEach(value -> metadataValues.put(value.getKey(), value.getValue()));
            String sourceId = metadataValues.get("sourceMessageId");
            boolean hasSource = false;
            try { hasSource = sourceId != null && Long.parseLong(sourceId) > 0 && Long.parseLong(sourceId) != messageId; }
            catch (NumberFormatException ignored) { }
            sourceMessageButton.setVisible(hasSource);
            sourceMessageButton.setText(t("View original #") + sourceId);
            boolean binary = "BASE64".equals(currentMessage.getPayloadFormat());
            charsetSelector.setEnabled(true);
            String charset = currentMessage.getCharset() == null ? "UTF-8" : currentMessage.getCharset();
            try { charset = Charset.forName(charset).name(); }
            catch (IllegalArgumentException invalid) { charset = "UTF-8"; }
            var choices = new java.util.LinkedHashSet<>(List.of("UTF-8", "windows-1251", "KOI8-R", "IBM866", "ISO-8859-1", "UTF-16", "UTF-16LE", "UTF-16BE"));
            choices.add(charset);
            charsetSelector.setItems(choices);
            charsetSelector.setValue(charset);
            loading=false;
            refreshPreview();

            open();
        } else {
            loading=false;
            com.vaadin.flow.component.notification.Notification.show(t("Message #") + messageId + t(" no longer exists"), 5000,
                    com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            close();
        }
    }

    private void refreshPreview() {
        if (loading || currentMessage == null || bodyVersion.getValue()==null) return;
        try {
            long id=currentMessage.getId(); Long jobId=bodyVersion.getValue().jobId();boolean original=revealOriginal.getValue();
            String charset=jobId==null?charsetSelector.getValue():null;
            var view=messageService.inspection().view(id,jobId,original,charset);
            historyGrid.setItems(messageService.history().events(id,original));
            payloadArea.setValue(view.text());
            var metadata=view.metadata().entrySet().stream().map(entry -> new MessageMetadata(currentMessage,entry.getKey(),entry.getValue())).toList();
            metadataGrid.setItems(metadata);
            previewHint.setText(original?t("Original payload · access recorded in audit"):t("Sensitive fields are masked on the server. Downloads use this same access policy."));
            downloadLink.setText(original?t("Download original payload"):t("Download masked payload"));
            downloadLink.setHref(DownloadHandler.fromInputStream(event -> {
                var fresh=messageService.inspection().view(id,jobId,original,charset);
                return new DownloadResponse(new ByteArrayInputStream(fresh.bytes()),"message-"+id+(original?".bin":"-masked.txt"),"application/octet-stream",fresh.bytes().length);
            }));
            downloadLink.setVisible(true);
        } catch(RuntimeException unavailable) {
            payloadArea.clear();metadataGrid.setItems(List.of());downloadLink.setVisible(false);
            previewHint.setText(t("Payload is unavailable, cannot be decoded, or access is denied. Refresh after resolving storage or permissions."));
        }
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
    private void retry(boolean acknowledgeUncertain) {
        try {
            Long id=currentMessage.getId(); messageService.retry(id,acknowledgeUncertain); openMessage(id);
            if(onChangeCallback!=null) onChangeCallback.accept(id);
        } catch(IllegalArgumentException invalid) {
            com.vaadin.flow.component.notification.Notification.show(t(invalid.getMessage()),5000,
                    com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
        }
    }

}
