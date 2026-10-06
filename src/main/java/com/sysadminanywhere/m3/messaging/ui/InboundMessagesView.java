package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@Route(value = "messages/inbound")
@MenuItem(order = 1, icon = "icons/message.svg", title = "Inbound", section = MenuSection.MESSAGING, parent = "Messages")
class InboundMessagesView extends VerticalLayout implements HasDynamicTitle {

    private final MessageService messageService;
    private final MessageMetadataRepository metadataRepository;
    private final MessageDetailDialog detailDialog;

    final ComboBox<MessageStatus> statusFilter;
    final Grid<Message> messageGrid;

    InboundMessagesView(MessageService messageService, MessageMetadataRepository metadataRepository,
            com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService submissions) {
        this.messageService = messageService;
        this.metadataRepository = metadataRepository;
        this.detailDialog = new MessageDetailDialog(messageService, metadataRepository, submissions);
        this.detailDialog.setOnMessageChanged(id -> refreshGrid());

        statusFilter = new ComboBox<>(t("Status"));
        statusFilter.setItems(MessageStatus.values()); statusFilter.setItemLabelGenerator(Translations::enumLabel);
        statusFilter.setClearButtonVisible(true);
        statusFilter.addValueChangeListener(event -> refreshGrid());

        var toolbar = new HorizontalLayout();
        toolbar.add(statusFilter, new Button(t("Refresh"), VaadinIcon.REFRESH.create(), event -> refreshGrid()));
        toolbar.setAlignItems(Alignment.END);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Translations.locale())
                .withZone(ZoneId.systemDefault());

        messageGrid = new Grid<>();
        messageGrid.setItems(query -> messageService.findByDirection(
                MessageDirection.INBOUND,
                statusFilter.getValue(),
                toSpringPageRequest(query).withSort(org.springframework.data.domain.Sort.by("createdAt", "id").descending())
        ).stream());
        messageGrid.addItemDoubleClickListener(event -> detailDialog.openMessage(event.getItem().getId()));
        messageGrid.addColumn(Message::getId).setHeader("ID").setWidth("85px").setFlexGrow(0);
        messageGrid.addColumn(item -> Translations.enumLabel(item.getStatus())).setHeader(t("Status")).setWidth("150px").setFlexGrow(0);
        messageGrid.addColumn(Message::getSourceSystem).setHeader(t("Source")).setWidth("150px").setFlexGrow(1);
        messageGrid.addColumn(Message::getTargetSystem).setHeader(t("Target")).setWidth("150px").setFlexGrow(1);
        messageGrid.addColumn(Message::getPayloadType).setHeader(t("Type")).setWidth("200px").setFlexGrow(1);
        messageGrid.addColumn(msg -> dateTimeFormatter.format(msg.getCreatedAt())).setHeader(t("Created"))
                .setWidth("200px").setFlexGrow(0);
        messageGrid.addComponentColumn(msg -> {
            var viewButton = new Button(t("View"), VaadinIcon.EYE.create());
            viewButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            viewButton.setTooltipText(t("View details"));
            viewButton.addClickListener(e -> detailDialog.openMessage(msg.getId()));
            return viewButton;
        }).setHeader(t("Actions")).setWidth("130px").setFlexGrow(0);
        messageGrid.setEmptyStateText(t("No inbound messages found"));
        messageGrid.setSizeFull();

        setSizeFull();
        add(toolbar, messageGrid);
    }

    private void refreshGrid() {
        messageGrid.getDataProvider().refreshAll();
    }
    @Override public String getPageTitle() { return t("Inbound Messages"); }
}
