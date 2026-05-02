package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.ViewTitle;
import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@Route(value = "")
@PageTitle("Messages")
@MenuItem(order = 1, icon = "icons/message.svg", title = "Messages", section = MenuSection.MESSAGING)
class MessageListView extends VerticalLayout {

    private final MessageService messageService;

    final ComboBox<MessageDirection> directionFilter;
    final ComboBox<MessageStatus> statusFilter;
    final Grid<Message> messageGrid;

    MessageListView(MessageService messageService) {
        this.messageService = messageService;

        directionFilter = new ComboBox<>("Direction");
        directionFilter.setItems(MessageDirection.values());
        directionFilter.setClearButtonVisible(true);
        directionFilter.addValueChangeListener(event -> refreshGrid());

        statusFilter = new ComboBox<>("Status");
        statusFilter.setItems(MessageStatus.values());
        statusFilter.setClearButtonVisible(true);
        statusFilter.addValueChangeListener(event -> refreshGrid());

        var toolbar = new HorizontalLayout();
        toolbar.add(new ViewTitle("Messages"), directionFilter, statusFilter);
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(getLocale())
                .withZone(ZoneId.systemDefault());

        messageGrid = new Grid<>();
        messageGrid.setItems(query -> messageService.list(toSpringPageRequest(query)).stream());
        messageGrid.addColumn(Message::getId).setHeader("ID");
        messageGrid.addColumn(Message::getDirection).setHeader("Direction");
        messageGrid.addColumn(Message::getStatus).setHeader("Status");
        messageGrid.addColumn(Message::getSourceSystem).setHeader("Source");
        messageGrid.addColumn(Message::getTargetSystem).setHeader("Target");
        messageGrid.addColumn(Message::getPayloadType).setHeader("Type");
        messageGrid.addColumn(msg -> dateTimeFormatter.format(msg.getCreatedAt())).setHeader("Created");
        messageGrid.setEmptyStateText("No messages found");
        messageGrid.setSizeFull();

        setSizeFull();
        add(toolbar, messageGrid);
    }

    private void refreshGrid() {
        messageGrid.getDataProvider().refreshAll();
    }
}
