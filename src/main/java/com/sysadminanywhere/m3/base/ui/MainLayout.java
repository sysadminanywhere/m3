package com.sysadminanywhere.m3.base.ui;

import com.sysadminanywhere.m3.base.ui.menu.MenuItemInfo;
import com.sysadminanywhere.m3.base.ui.menu.MenuItemRegistry;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Unit;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.avatar.Avatar;
import com.vaadin.flow.component.avatar.AvatarVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.SvgIcon;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;

import java.util.*;
import java.util.stream.Collectors;

@Layout
public final class MainLayout extends AppLayout {

    private final MenuItemRegistry menuItemRegistry;
    private final Map<MenuSection, SideNavItem> sectionItems = new HashMap<>();

    public MainLayout(MenuItemRegistry menuItemRegistry) {
        this.menuItemRegistry = menuItemRegistry;
        setPrimarySection(Section.DRAWER);
        addToDrawer(createApplicationHeader(), createApplicationDrawer(), createApplicationFooter());
    }

    private Component createApplicationHeader() {
        // TODO Replace with real application logo and name
        var appLogo = new Avatar("M³");
        appLogo.addClassName("app-logo");
        appLogo.addThemeVariants(AvatarVariant.AURA_FILLED, AvatarVariant.XSMALL);

        // Message Manager Middleware
        var appName = new Span("M³");
        appName.addClassName("app-name");

        var header = new HorizontalLayout(appLogo, appName);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setPadding(true);
        return header;
    }

    private Component createApplicationDrawer() {
        var scroller = new Scroller(createSideNav());
        scroller.addThemeVariants(ScrollerVariant.OVERFLOW_INDICATORS);
        return scroller;
    }

    private Component createApplicationFooter() {
        var footer = new VerticalLayout(new Span("Made with ❤️ with Vaadin"));
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.addClassName("app-footer");
        return footer;
    }

    private SideNav createSideNav() {
        var nav = new SideNav();
        nav.setMinWidth(200, Unit.PIXELS);

        if (menuItemRegistry == null) {
            return nav;
        }

        Map<MenuSection, List<MenuItemInfo>> itemsBySection = menuItemRegistry.getMenuItemsBySection();

        menuItemRegistry.getSections().forEach(section -> {
            SideNavItem sectionItem = new SideNavItem(section.getTitle());
            sectionItems.put(section, sectionItem);

            List<MenuItemInfo> items = itemsBySection.getOrDefault(section, List.of());

            // Group items by parent
            Map<String, List<MenuItemInfo>> itemsByParent = items.stream()
                    .collect(Collectors.groupingBy(
                            item -> item.parent() != null && !item.parent().isEmpty() ? item.parent() : "",
                            Collectors.toList()
                    ));

            // Map to store parent menu items
            Map<String, SideNavItem> parentItems = new HashMap<>();

            // First pass: create parent items
            itemsByParent.keySet().stream()
                    .filter(parent -> !parent.isEmpty())
                    .forEach(parentTitle -> {
                        SideNavItem parentItem = new SideNavItem(parentTitle);
                        parentItems.put(parentTitle, parentItem);
                        sectionItem.addItem(parentItem);
                    });

            // Second pass: add items (both root and children)
            items.stream()
                    .sorted(Comparator.comparingInt(MenuItemInfo::order))
                    .forEach(item -> {
                        SideNavItem navItem = createSideNavItem(item);
                        if (item.parent() != null && !item.parent().isEmpty()) {
                            // Add as child to parent
                            SideNavItem parent = parentItems.get(item.parent());
                            if (parent != null) {
                                parent.addItem(navItem);
                            } else {
                                sectionItem.addItem(navItem);
                            }
                        } else {
                            // Add directly to section
                            sectionItem.addItem(navItem);
                        }
                    });

            nav.addItem(sectionItem);
        });

        return nav;
    }

    private SideNavItem createSideNavItem(MenuItemInfo menuItem) {
        if (!menuItem.icon().isEmpty()) {
            Component icon = null;
            if (menuItem.icon().contains(".svg")) {
                icon = new SvgIcon(menuItem.icon());
            } else {
                icon = new Icon(menuItem.icon());
            }
            return new SideNavItem(menuItem.title(), menuItem.path(), icon);
        } else {
            return new SideNavItem(menuItem.title(), menuItem.path());
        }
    }
}
