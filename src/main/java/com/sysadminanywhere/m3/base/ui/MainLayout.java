package com.sysadminanywhere.m3.base.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.sysadminanywhere.m3.base.ui.menu.MenuItemInfo;
import com.sysadminanywhere.m3.base.ui.menu.MenuItemRegistry;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
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

/** Shared navigation and responsive workspace for every application view. */
@jakarta.annotation.security.PermitAll
@Layout
@JavaScript("context://theme-preference.js")
@JavaScript("context://unsaved-changes.js")
public final class MainLayout extends Div implements RouterLayout, AfterNavigationObserver {
    private final MenuItemRegistry menuItemRegistry;
    private final Div secondaryNavigation = new Div();
    private final Div viewContainer = new Div();
    private final H1 pageTitle = new H1();
    private final Map<MenuSection, NativeButton> sectionButtons = new EnumMap<>(MenuSection.class);
    private final Map<String, RouterLink> pageLinks = new LinkedHashMap<>();
    private MenuSection activeSection;
    private String currentPath = "";
    private Component currentView;

    public MainLayout(MenuItemRegistry menuItemRegistry, com.vaadin.flow.spring.security.AuthenticationContext authenticationContext) {
        this.menuItemRegistry = menuItemRegistry;
        addClassName("m3-shell");
        Anchor skipLink = new Anchor("#m3-main-content", t("Skip to content"));
        skipLink.setRouterIgnore(true);
        skipLink.addClassName("skip-link");
        // A fragment-only URL resolves against Vaadin's base URL, not the current route.
        skipLink.getElement().executeJs("""
                this.onclick = event => {
                    event.preventDefault();
                    document.getElementById('m3-main-content')?.focus({preventScroll: true});
                };
                """);
        add(skipLink);
        Div rail = new Div();
        rail.addClassName("primary-navigation");
        rail.getElement().setAttribute("role", "navigation");
        rail.getElement().setAttribute("aria-label", t("Sections"));
        BrandMark logo = new BrandMark();
        logo.addClassName("navigation-logo");
        rail.add(logo);
        Div sections = new Div();
        sections.addClassName("primary-nav-sections");
        for (MenuSection section : menuItemRegistry.getSections()) {
            if (section == MenuSection.SETTINGS || menuItemRegistry.getMenuItems().stream().noneMatch(item -> item.section() == section && allowed(item))) continue;
            NativeButton button = new NativeButton();
            button.addClassName("primary-nav-button");
            button.getElement().setAttribute("aria-label", t(section.getTitle()));
            button.getElement().setAttribute("title", t(section.getTitle()));
            String iconName = switch (section) {
                case MESSAGING -> "globe";
                case SETTINGS -> "settings";
                case ADMINISTRATION -> "admin";
            };
            button.add(icon(iconName), new Span(section == MenuSection.ADMINISTRATION ? t("Admin") : t(section.getTitle())));
            button.addClickListener(event -> navigateToSection(section));
            sectionButtons.put(section, button);
            sections.add(button);
        }
        rail.add(sections);
        NativeButton settingsButton = new NativeButton();
        settingsButton.addClassName("primary-nav-button");
        settingsButton.addClassName("settings-nav-button");
        settingsButton.add(icon("settings"), new Span(t("Settings")));
        settingsButton.getElement().setAttribute("aria-label", t("Settings"));
        settingsButton.getElement().setAttribute("title", t("Settings"));
        settingsButton.addClickListener(event -> navigateToSection(MenuSection.SETTINGS));
        sectionButtons.put(MenuSection.SETTINGS, settingsButton);
        rail.add(settingsButton);
        secondaryNavigation.addClassName("secondary-navigation");
        secondaryNavigation.getElement().setAttribute("role", "navigation");
        secondaryNavigation.getElement().setAttribute("aria-label", t("Pages"));
        pageTitle.addClassName("app-page-title");
        viewContainer.addClassName("view-container");
        var logout = new Button(t("Sign out"), event -> authenticationContext.logout());
        Div pageHeader = new Div(pageTitle, logout);
        pageHeader.addClassName("workspace-header");
        Div main = new Div(pageHeader, viewContainer);
        main.addClassName("main-panel");
        main.getElement().setAttribute("role", "main");
        main.setId("m3-main-content");
        main.getElement().setAttribute("tabindex", "-1");
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
        String menuPath = currentPath.startsWith("channel/") || currentPath.equals("channel") ? "channels" : currentPath;
        MenuItemInfo currentItem = menuItemRegistry.getMenuItems().stream()
                .filter(item -> item.path().equals(menuPath) || menuPath.startsWith(item.path() + "/"))
                .max(Comparator.comparingInt(item -> item.path().length())).orElse(null);
        PageTitle title = currentView == null ? null : currentView.getClass().getAnnotation(PageTitle.class);
        String text = currentView instanceof com.vaadin.flow.router.HasDynamicTitle dynamic ? dynamic.getPageTitle() : title != null ? title.value() : currentItem != null ? currentItem.title() : "M3";
        pageTitle.setText(t(text));
        getUI().ifPresent(ui -> ui.getPage().setTitle(t(text)));
        if (currentItem != null && currentItem.section() != activeSection) showSection(currentItem.section());
        updateCurrentLink();
    }

    private void navigateToSection(MenuSection section) {
        showSection(section);
        menuItemRegistry.getMenuItems().stream()
                .filter(item -> item.section() == section && allowed(item))
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
        H3 title = new H3(t(section.getTitle()));
        title.addClassName("secondary-navigation-title");
        Div header = new Div(title);
        header.addClassName("secondary-navigation-header");
        Div nav = new Div();
        nav.addClassName("section-side-nav");
        menuItemRegistry.getMenuItems().stream().filter(item -> item.section() == section && allowed(item))
                .sorted(Comparator.comparingInt(MenuItemInfo::order)).forEach(item -> {
                    RouterLink link = new RouterLink();
                    link.setRoute(item.viewClass().asSubclass(Component.class));
                    link.add(icon(switch (item.title()) {
                        case "Overview" -> "globe";
                        case "Inbound" -> "inbound";
                        case "Outbound" -> "outbound";
                        case "Rules" -> "filter";
                        case "Channels" -> "link";
                        case "Workers" -> "cube";
                        case "Settings" -> "settings";
                        default -> "file";
                    }), new Span(t(item.title())));
                    link.addClassName("section-nav-link");
                    pageLinks.put(item.path(), link);
                    nav.add(link);
                });
        secondaryNavigation.add(header, nav);
        updateCurrentLink();
    }

    private void updateCurrentLink() {
        String menuPath = currentPath.startsWith("channel/") || currentPath.equals("channel") ? "channels" : currentPath;
        pageLinks.forEach((path, link) -> {
            boolean current = path.equals(menuPath) || menuPath.startsWith(path + "/");
            link.setClassName("current", current);
            if (current) link.getElement().setAttribute("aria-current", "page");
            else link.getElement().removeAttribute("aria-current");
        });
    }

    private boolean allowed(MenuItemInfo item) {
        var roles = item.viewClass().getAnnotation(jakarta.annotation.security.RolesAllowed.class);
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return roles == null || auth != null && auth.getAuthorities().stream().anyMatch(a -> java.util.Arrays.asList(roles.value()).contains(a.getAuthority().replace("ROLE_", "")));
    }

    private Component icon(String name) {
        Image image = new Image("icons/navigation/" + name + ".svg", "");
        image.addClassName("navigation-icon");
        image.getElement().setAttribute("aria-hidden", "true");
        return image;
    }

}
