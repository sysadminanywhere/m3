package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.base.ui.ResponsiveGrid;
import com.sysadminanywhere.m3.messaging.service.MessageSummary;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.sysadminanywhere.m3.messaging.repository.MessageMetadataRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

abstract class MessageListView extends VerticalLayout implements com.vaadin.flow.router.BeforeEnterObserver {

    private final MessageDetailDialog detailDialog;

    private final MessageFilterBar filters;
    final Grid<MessageSummary> messageGrid;

    MessageListView(MessageDirection direction, String route, MessageService messageService, MessageMetadataRepository metadataRepository,
            com.sysadminanywhere.m3.messaging.outbound.OutboundSubmissionService submissions) {
        this.detailDialog = new MessageDetailDialog(messageService, metadataRepository, submissions);
        this.detailDialog.setOnMessageChanged(id -> refreshGrid());

        filters = new MessageFilterBar(route, this::refreshGrid);

        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Translations.locale())
                .withZone(ZoneId.systemDefault());

        messageGrid = new Grid<>();
        messageGrid.setItems(query -> {
            // Vaadin requires pagination to be read even when invalid filters return no rows.
            var page = toSpringPageRequest(query).withSort(org.springframework.data.domain.Sort.by("createdAt", "id").descending());
            return !filters.valid() ? java.util.stream.Stream.empty()
                    : messageService.searchSummary(direction, filters.active(), page).stream();
        });
        messageGrid.addItemDoubleClickListener(event -> detailDialog.openMessage(event.getItem().getId()));
        messageGrid.addColumn(MessageSummary::getId).setHeader("ID").setWidth("85px").setFlexGrow(0);
        messageGrid.addColumn(row -> row.archived()?t("Archive"):t("Hot storage")).setHeader(t("Storage")).setWidth("120px").setFlexGrow(0);
        messageGrid.addComponentColumn(item -> new MessageStatusBadge(item.getStatus())).setHeader(t("Status")).setWidth("150px").setFlexGrow(0);
        messageGrid.addColumn(MessageSummary::getSourceSystem).setHeader(t("Source")).setWidth("150px").setFlexGrow(1);
        messageGrid.addColumn(MessageSummary::getTargetSystem).setHeader(t("Target")).setWidth("150px").setFlexGrow(1);
        messageGrid.addColumn(MessageSummary::getPayloadType).setHeader(t("Type")).setWidth("200px").setFlexGrow(1);
        messageGrid.addColumn(msg -> dateTimeFormatter.format(msg.getCreatedAt())).setHeader(t("Created"))
                .setWidth("200px").setFlexGrow(0);
        messageGrid.addComponentColumn(msg -> {
            var viewButton = new Button(t("View"), VaadinIcon.EYE.create());
            viewButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            viewButton.setTooltipText(t("View details"));
            viewButton.addClickListener(e -> detailDialog.openMessage(msg.getId()));
            return viewButton;
        }).setHeader(t("Actions")).setWidth("130px").setFlexGrow(0).setFrozenToEnd(true);
        messageGrid.setEmptyStateText(t("No messages match the filters"));
        messageGrid.setSizeFull();
        ResponsiveGrid.configure(messageGrid);

        setSizeFull();
        add(filters, messageGrid);
    }

    private void refreshGrid() {
        messageGrid.getDataProvider().refreshAll();
    }
    @Override public void beforeEnter(com.vaadin.flow.router.BeforeEnterEvent event) {
        filters.load(event.getLocation().getQueryParameters());
        refreshGrid();
    }
}
