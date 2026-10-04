package com.sysadminanywhere.m3.base.ui;

import com.sysadminanywhere.m3.base.ui.menu.MenuItemInfo;
import com.sysadminanywhere.m3.base.ui.menu.MenuItemRegistry;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.router.RouterLink;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** The navigation rail and workspace share one frame, matching the reference layout. */
@Layout
public final class MainLayout extends Div implements RouterLayout, AfterNavigationObserver {
    private final MenuItemRegistry menuItemRegistry;
    private final Div secondaryNavigation = new Div();
    private final Div viewContainer = new Div();
    private final H3 pageTitle = new H3();
    private final Map<MenuSection, NativeButton> sectionButtons = new EnumMap<>(MenuSection.class);
    private final Map<String, RouterLink> pageLinks = new LinkedHashMap<>();
    private MenuSection activeSection;
    private String currentPath = "";
    private Component currentView;

    public MainLayout(MenuItemRegistry menuItemRegistry) {
        this.menuItemRegistry = menuItemRegistry;
        addClassName("m3-shell");
        Div rail = new Div();
        rail.addClassName("primary-navigation");
        rail.getElement().setAttribute("role", "navigation");
        rail.getElement().setAttribute("aria-label", "Sections");
        Image logo = new Image("icons/m3-cube.svg", "M3");
        logo.addClassName("navigation-logo");
        rail.add(logo);
        Div sections = new Div();
        sections.addClassName("primary-nav-sections");
        for (MenuSection section : menuItemRegistry.getSections()) {
            NativeButton button = new NativeButton();
            button.addClassName("primary-nav-button");
            button.getElement().setAttribute("aria-label", section.getTitle());
            String iconName = switch (section) {
                case MESSAGING -> "globe";
                case SETTINGS -> "messages";
                case ADMINISTRATION -> "settings";
            };
            button.add(icon(iconName), new Span(section == MenuSection.ADMINISTRATION ? "Admin" : section.getTitle()));
            button.addClickListener(event -> navigateToSection(section));
            sectionButtons.put(section, button);
            sections.add(button);
        }
        rail.add(sections);
        secondaryNavigation.addClassName("secondary-navigation");
        secondaryNavigation.getElement().setAttribute("role", "navigation");
        secondaryNavigation.getElement().setAttribute("aria-label", "Pages");
        pageTitle.addClassName("app-page-title");
        viewContainer.addClassName("view-container");
        Div main = new Div(pageTitle, viewContainer);
        main.addClassName("main-panel");
        main.getElement().setAttribute("role", "main");
        Div workspace = new Div(secondaryNavigation, main);
        workspace.addClassName("workspace-frame");
        add(rail, workspace);
        var sectionsList = menuItemRegistry.getSections();
        if (!sectionsList.isEmpty()) showSection(sectionsList.get(0));
    }

    @Override
    public void showRouterLayoutContent(HasElement content) {
        viewContainer.removeAll();
        currentView = content instanceof Component component ? component : null;
        if (content != null) viewContainer.getElement().appendChild(content.getElement());
    }

    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        currentPath = event.getLocation().getPath();
        MenuItemInfo currentItem = menuItemRegistry.getMenuItems().stream()
                .filter(item -> item.path().equals(currentPath) || currentPath.startsWith(item.path() + "/"))
                .findFirst().orElse(null);
        PageTitle title = currentView == null ? null : currentView.getClass().getAnnotation(PageTitle.class);
        pageTitle.setText(title != null ? title.value() : currentItem != null ? currentItem.title() : "M3");
        if (currentItem != null && currentItem.section() != activeSection) showSection(currentItem.section());
        else if (currentPath.startsWith("channel/")) showSection(MenuSection.SETTINGS);
        updateCurrentLink();
    }

    private void navigateToSection(MenuSection section) {
        showSection(section);
        menuItemRegistry.getMenuItems().stream()
                .filter(item -> item.section() == section)
                .min(Comparator.comparingInt(MenuItemInfo::order))
                .ifPresent(item -> getUI().ifPresent(ui -> ui.navigate(item.viewClass().asSubclass(Component.class))));
    }

    private void showSection(MenuSection section) {
        activeSection = section;
        sectionButtons.forEach((itemSection, button) -> {
            button.setClassName("active", itemSection == section);
            button.getElement().setAttribute("aria-pressed", String.valueOf(itemSection == section));
        });
        secondaryNavigation.removeAll();
        pageLinks.clear();
        H3 title = new H3(section.getTitle());
        title.addClassName("secondary-navigation-title");
        NativeButton create = new NativeButton();
        create.addClassName("secondary-navigation-create");
        create.getElement().setAttribute("aria-label", "Create new item");
        create.add(icon("plus"));
        create.addClickListener(event -> triggerPageCreateAction());
        Div header = new Div(title, create);
        header.addClassName("secondary-navigation-header");
        Div nav = new Div();
        nav.addClassName("section-side-nav");
        menuItemRegistry.getMenuItems().stream().filter(item -> item.section() == section)
                .sorted(Comparator.comparingInt(MenuItemInfo::order)).forEach(item -> {
                    RouterLink link = new RouterLink();
                    link.setRoute(item.viewClass().asSubclass(Component.class));
                    link.add(icon(switch (item.title()) {
                        case "Inbound" -> "inbound";
                        case "Outbound" -> "outbound";
                        case "Rules" -> "filter";
                        case "Channels" -> "link";
                        case "Workers" -> "cube";
                        default -> "file";
                    }), new Span(item.title()));
                    link.addClassName("section-nav-link");
                    pageLinks.put(item.path(), link);
                    nav.add(link);
                });
        secondaryNavigation.add(header, nav);
        updateCurrentLink();
    }

    private void updateCurrentLink() {
        pageLinks.forEach((path, link) -> {
            boolean current = path.equals(currentPath) || currentPath.startsWith(path + "/");
            link.setClassName("current", current);
            if (current) link.getElement().setAttribute("aria-current", "page");
            else link.getElement().removeAttribute("aria-current");
        });
    }

    private Image icon(String name) {
        Image image = new Image("icons/navigation/" + name + ".svg", "");
        image.addClassName("navigation-icon");
        image.getElement().setAttribute("aria-hidden", "true");
        return image;
    }

    private void triggerPageCreateAction() {
        Button action = findPrimaryAction(currentView);
        if (action == null) Notification.show("No create action is available on this page");
        else action.getElement().callJsFunction("click");
    }

    private Button findPrimaryAction(Component component) {
        if (component == null) return null;
        if (component instanceof Button button && button.getThemeNames().contains("primary")) return button;
        return component.getChildren().map(this::findPrimaryAction).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }
}
