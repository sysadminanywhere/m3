package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.base.ui.ViewTitle;
import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.ChannelSettingsService;
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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import static com.vaadin.flow.spring.data.VaadinSpringDataHelpers.toSpringPageRequest;

@Route(value = "channels")
@PageTitle("Channels")
@MenuItem(order = 2, icon = "icons/channel.svg", title = "Channels", section = MenuSection.SETTINGS)
class ChannelListView extends VerticalLayout {

    private final ChannelSettingsService channelSettingsService;

    final ComboBox<ChannelType> typeFilter;
    final Grid<ChannelSettings> channelGrid;

    ChannelListView(ChannelSettingsService channelSettingsService) {
        this.channelSettingsService = channelSettingsService;

        typeFilter = new ComboBox<>("Type");
        typeFilter.setItems(ChannelType.values());
        typeFilter.setClearButtonVisible(true);
        typeFilter.addValueChangeListener(event -> refreshGrid());

        var addButton = new Button("Add Channel", VaadinIcon.PLUS.create(), event -> addChannel());
        addButton.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout();
        toolbar.add(new ViewTitle("Channels"), typeFilter, addButton);
        toolbar.setWrap(true);
        toolbar.setWidthFull();

        channelGrid = new Grid<>();
        channelGrid.setItems(query -> {
            var pageRequest = toSpringPageRequest(query);
            var page = typeFilter.getValue() != null
                    ? channelSettingsService.findByType(typeFilter.getValue())
                    : channelSettingsService.findAll();
            return page.stream()
                    .skip(pageRequest.getOffset())
                    .limit(pageRequest.getPageSize());
        });
        channelGrid.addColumn(ChannelSettings::getName).setHeader("Name").setSortable(true);
        channelGrid.addColumn(ChannelSettings::getChannelType).setHeader("Type").setSortable(true);
        channelGrid.addColumn(ChannelSettings::getDirection).setHeader("Direction").setSortable(true);
        channelGrid.addColumn(settings -> settings.getEnabled() ? "Enabled" : "Disabled")
                .setHeader("Status").setSortable(true);
        channelGrid.addColumn(ChannelSettings::getDescription).setHeader("Description");
        channelGrid.addComponentColumn(this::createActionButtons).setHeader("Actions");
        channelGrid.setEmptyStateText("No channels found");
        channelGrid.setSizeFull();

        setSizeFull();
        add(toolbar, channelGrid);
    }

    private HorizontalLayout createActionButtons(ChannelSettings channel) {
        var editButton = new Button(VaadinIcon.EDIT.create());
        editButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        editButton.addClickListener(event -> getUI().ifPresent(ui -> ui.navigate("channel/" + channel.getId())));

        var toggleButton = new Button(channel.getEnabled() ? VaadinIcon.PAUSE.create() : VaadinIcon.PLAY.create());
        toggleButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
        toggleButton.addClickListener(event -> toggleChannel(channel));

        var deleteButton = new Button(VaadinIcon.TRASH.create());
        deleteButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        deleteButton.addClickListener(event -> deleteChannel(channel));

        return new HorizontalLayout(editButton, toggleButton, deleteButton);
    }

    private void refreshGrid() {
        channelGrid.getDataProvider().refreshAll();
    }

    private void addChannel() {
        getUI().ifPresent(ui -> ui.navigate("channel"));
    }

    private void toggleChannel(ChannelSettings channel) {
        channelSettingsService.toggleEnabled(channel.getId());
        refreshGrid();
        Notification.show("Channel " + (channel.getEnabled() ? "disabled" : "enabled"), 3000, Notification.Position.BOTTOM_END)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void deleteChannel(ChannelSettings channel) {
        var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle("Confirm Delete");
        dialog.add("Are you sure you want to delete channel '" + channel.getName() + "'?");

        var deleteButton = new Button("Delete", event -> {
            try {
                channelSettingsService.deleteChannel(channel.getId());
                refreshGrid();
                dialog.close();
                Notification.show("Channel deleted", 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception e) {
                Notification.show("Error deleting channel: " + e.getMessage(), 3000, Notification.Position.BOTTOM_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        deleteButton.addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.LUMO_ERROR);

        var cancelButton = new Button("Cancel", event -> dialog.close());

        dialog.getFooter().add(cancelButton, deleteButton);
        dialog.open();
    }
}
