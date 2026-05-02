package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.ViewTitle;
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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@Route(value = "messages/inbound")
@PageTitle("Inbound Messages")
@MenuItem(order = 1, icon = "icons/message.svg", title = "Inbound", section = MenuSection.MESSAGING, parent = "Messages")
class InboundMessagesView extends VerticalLayout {

    private final MessageService messageService;
    private final MessageMetadataRepository metadataRepository;
    private final MessageDetailDialog detailDialog;

    final ComboBox<MessageStatus> statusFilter;
    final Grid<Message> messageGrid;

    InboundMessagesView(MessageService messageService, MessageMetadataRepository metadataRepository) {
        this.messageService = messageService;
        this.metadataRepository = metadataRepository;
        this.detailDialog = new MessageDetailDialog(messageService, metadataRepository);
        this.detailDialog.setOnDeleteCallback(id -> refreshGrid());

        statusFilter = new ComboBox<>("Status");
        statusFilter.setItems(MessageStatus.values());
        statusFilter.setClearButtonVisible(true);
        statusFilter.addValueChangeListener(event -> refreshGrid());

        var toolbar = new HorizontalLayout();
        toolbar.add(new ViewTitle("Inbound Messages"), statusFilter);
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(getLocale())
                .withZone(ZoneId.systemDefault());

        messageGrid = new Grid<>();
        messageGrid.setItems(query -> messageService.findByDirection(
                MessageDirection.INBOUND,
                toSpringPageRequest(query)
        ).stream().filter(m -> statusFilter.getValue() == null || m.getStatus() == statusFilter.getValue()));
        messageGrid.addColumn(Message::getId).setHeader("ID");
        messageGrid.addColumn(Message::getStatus).setHeader("Status");
        messageGrid.addColumn(Message::getSourceSystem).setHeader("Source");
        messageGrid.addColumn(Message::getTargetSystem).setHeader("Target");
        messageGrid.addColumn(Message::getPayloadType).setHeader("Type");
        messageGrid.addColumn(msg -> dateTimeFormatter.format(msg.getCreatedAt())).setHeader("Created");
        messageGrid.addComponentColumn(msg -> {
            var viewButton = new Button(VaadinIcon.EYE.create());
            viewButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            viewButton.setTooltipText("View details");
            viewButton.addClickListener(e -> detailDialog.openMessage(msg.getId()));
            return viewButton;
        }).setHeader("Actions").setWidth("80px");
        messageGrid.setEmptyStateText("No inbound messages found");
        messageGrid.setSizeFull();

        setSizeFull();
        add(toolbar, messageGrid);
    }

    private void refreshGrid() {
        messageGrid.getDataProvider().refreshAll();
    }
}
