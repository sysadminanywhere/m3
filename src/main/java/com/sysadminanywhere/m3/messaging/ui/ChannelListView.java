package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
import com.sysadminanywhere.m3.messaging.source.SourceHealth;
import com.sysadminanywhere.m3.messaging.domain.ChannelDirection;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@Route(value = "channels")
@MenuItem(order = 2, icon = "icons/channel.svg", title = "Channels", section = MenuSection.ADMINISTRATION)
class ChannelListView extends VerticalLayout implements HasDynamicTitle {

    private final ChannelSettingsService channelSettingsService;

    final ComboBox<ChannelType> typeFilter;
    final Grid<ChannelSettings> channelGrid;

    ChannelListView(ChannelSettingsService channelSettingsService, SourceHealth sourceHealth) {
        this.channelSettingsService = channelSettingsService;

        typeFilter = new ComboBox<>(t("Type"));
        typeFilter.setItems(ChannelType.values()); typeFilter.setItemLabelGenerator(Translations::enumLabel);
        typeFilter.setClearButtonVisible(true);
        typeFilter.addValueChangeListener(event -> refreshGrid());

        var addButton = new Button(t("Add Channel"), event -> addChannel());
        addButton.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout();
        toolbar.add(typeFilter, new Button(t("Refresh"), event -> refreshGrid()), addButton);
        toolbar.addClassName("page-toolbar");
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        channelGrid = new Grid<>();
        channelGrid.setItems(query -> {
            var pageRequest = toSpringPageRequest(query);
            var sort = pageRequest.getSort().and(org.springframework.data.domain.Sort.by("id"));
            return channelSettingsService.page(typeFilter.getValue(), org.springframework.data.domain.PageRequest.of(
                    pageRequest.getPageNumber(), pageRequest.getPageSize(), sort)).stream();
        });
        channelGrid.addColumn(ChannelSettings::getName).setHeader(t("Name")).setSortProperty("name")
                .setWidth("160px").setFlexGrow(1);
        channelGrid.addColumn(item -> Translations.enumLabel(item.getChannelType())).setHeader(t("Type")).setSortProperty("channelType")
                .setWidth("150px").setFlexGrow(0);
        channelGrid.addColumn(item -> Translations.enumLabel(item.getDirection())).setHeader(t("Direction")).setSortProperty("direction")
                .setWidth("160px").setFlexGrow(0);
        channelGrid.addComponentColumn(settings -> {
            boolean inbound = settings.getDirection() == ChannelDirection.INBOUND;
            String label = inbound ? t("Rules") : settings.getEnabled() ? t("Enabled") : t("Disabled");
            var status = new com.vaadin.flow.component.html.Span(label);
            if (inbound) status.getElement().setAttribute("title", t("Managed by loading rules"));
            return status;
        }).setHeader(t("Status")).setWidth("130px").setFlexGrow(0);
        channelGrid.addColumn(ChannelSettings::getDescription).setHeader(t("Description"))
                .setWidth("200px").setFlexGrow(1);
        channelGrid.addComponentColumn(this::createActionButtons).setHeader(t("Actions"))
                .setWidth("160px").setFlexGrow(0).setFrozenToEnd(true);
        channelGrid.setEmptyStateText(t("No channels found"));
        channelGrid.setSizeFull();

        setSizeFull();
        add(toolbar, channelGrid);
    }

    private HorizontalLayout createActionButtons(ChannelSettings channel) {
        var editButton = new Button(VaadinIcon.EDIT.create());
        editButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        editButton.addClickListener(event -> openChannelDialog(channel));

        var toggleButton = new Button(channel.getEnabled() ? VaadinIcon.PAUSE.create() : VaadinIcon.PLAY.create());
        toggleButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        toggleButton.addClickListener(event -> toggleChannel(channel));

        var deleteButton = new Button(VaadinIcon.TRASH.create());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        deleteButton.addClickListener(event -> deleteChannel(channel));

        toggleButton.setVisible(channel.getDirection()==ChannelDirection.OUTBOUND);
        toggleButton.setTooltipText(t("Change outbound availability"));
        return new HorizontalLayout(editButton, toggleButton, deleteButton);
    }

    private void refreshGrid() {
        channelGrid.getDataProvider().refreshAll();
    }

    private void addChannel() {
        openChannelDialog(null);
    }

    private void openChannelDialog(ChannelSettings channel) {
        var dialog = new ChannelDialog(channelSettingsService, channel, v -> refreshGrid());
        dialog.open();
    }

    private void toggleChannel(ChannelSettings channel) {
        channelSettingsService.toggleEnabled(channel.getId());
        refreshGrid();
        Notification.show(t("Channel ") + (channel.getEnabled() ? t("disabled") : t("enabled")), 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void deleteChannel(ChannelSettings channel) {
        var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(t("Confirm Delete"));
        dialog.add(t("Are you sure you want to delete channel '") + channel.getName() + "'?");

        var deleteButton = new Button(t("Delete"), event -> {
            try {
                channelSettingsService.deleteChannel(channel.getId());
                refreshGrid();
                dialog.close();
                Notification.show(t("Channel deleted"), 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception e) {
                Notification.show(t("Error deleting channel: ") + t(e.getMessage()), 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        deleteButton.addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.LUMO_ERROR);

        var cancelButton = new Button(t("Cancel"), event -> dialog.close());

        dialog.getFooter().add(cancelButton, deleteButton);
        dialog.open();
    }
    @Override public String getPageTitle() { return t("Channels"); }
}
