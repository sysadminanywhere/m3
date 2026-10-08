package com.sysadminanywhere.m3.base.ui.menu;

import com.vaadin.flow.router.RouteData;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class MenuItemRegistry {

    private List<MenuItemInfo> menuItems = null;

    private void ensureMenuItems() {
        if (menuItems != null) {
            return;
        }

        menuItems = new ArrayList<>();

        if (VaadinService.getCurrent() == null) {
            return;
        }

        RouteRegistry registry = VaadinService.getCurrent().getRouter().getRegistry();

        for (RouteData routeData : registry.getRegisteredRoutes()) {
            Class<?> viewClass = routeData.getNavigationTarget();
            MenuItem menuItem = viewClass.getAnnotation(MenuItem.class);

            if (menuItem != null) {
                String path = routeData.getTemplate();
                menuItems.add(new MenuItemInfo(
                        menuItem.title(),
                        menuItem.icon(),
                        menuItem.order(),
                        menuItem.section(),
                        menuItem.parent(),
                        path,
                        viewClass
                ));
            }
        }

        menuItems.sort(Comparator
                .comparingInt((MenuItemInfo m) -> m.section().getOrder())
                .thenComparingInt(MenuItemInfo::order));
    }

    public List<MenuItemInfo> getMenuItems() {
        ensureMenuItems();
        return Collections.unmodifiableList(menuItems != null ? menuItems : List.of());
    }

    public Map<MenuSection, List<MenuItemInfo>> getMenuItemsBySection() {
        ensureMenuItems();
        return menuItems != null
                ? menuItems.stream().collect(Collectors.groupingBy(MenuItemInfo::section, Collectors.toList()))
                : Map.of();
    }

    public List<MenuSection> getSections() {
        ensureMenuItems();
        return menuItems != null
                ? menuItems.stream()
                        .map(MenuItemInfo::section)
                        .distinct()
                        .sorted(Comparator.comparingInt(MenuSection::getOrder))
                        .collect(Collectors.toList())
                : List.of();
    }

}
