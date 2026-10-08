package com.sysadminanywhere.m3.base.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.sysadminanywhere.m3.base.i18n.Translations;
import com.sysadminanywhere.m3.base.ui.menu.MenuItem;
import com.sysadminanywhere.m3.base.ui.menu.MenuSection;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;

import java.util.List;
import java.util.Locale;

@jakarta.annotation.security.PermitAll
@Route("settings")
@JavaScript("context://theme-preference.js")
@MenuItem(title = "Settings", icon = "icons/settings.svg", order = 1, section = MenuSection.SETTINGS)
class SettingsView extends VerticalLayout implements HasDynamicTitle {
    private static final List<String> THEMES = List.of("light", "dark", "system");
    private final ComboBox<String> themeField;

    SettingsView() {
        addClassName("settings-page");
        setPadding(false);
        setSpacing(false);

        var heading = new H2(t("Preferences"));
        heading.addClassName("settings-page-heading");
        var intro = new Paragraph(t("Personalize the language and appearance of the application."));
        intro.addClassName("settings-page-intro");

        var language = new ComboBox<Locale>(t("Language"));
        language.setItems(Translations.LANGUAGES);
        language.setItemLabelGenerator(locale -> locale.getDisplayLanguage(locale));
        language.setValue(Translations.locale());
        language.setWidthFull();
        language.addValueChangeListener(event -> {
            if (!event.isFromClient()) return;
            if (event.getValue() == null) { language.setValue(event.getOldValue()); return; }
            getUI().ifPresent(ui -> {
                var locale = event.getValue();
                ui.setLocale(locale);
                ui.getSession().setLocale(locale);
                ui.getPage().executeJs("document.cookie = 'm3-language=' + $0 + '; Path=/; Max-Age=31536000; SameSite=Lax'; window.location.reload()", locale.getLanguage());
            });
        });

        themeField = new ComboBox<>(t("Theme"));
        themeField.setItems(THEMES);
        themeField.setItemLabelGenerator(value -> t(switch (value) {
            case "dark" -> "Dark theme";
            case "system" -> "System theme";
            default -> "Light theme";
        }));
        themeField.setValue("system");
        themeField.setWidthFull();
        themeField.addValueChangeListener(event -> {
            if (!event.isFromClient()) return;
            if (event.getValue() == null) { themeField.setValue(event.getOldValue()); return; }
            getUI().ifPresent(ui -> ui.getPage().executeJs("window.m3Theme.save($0)", event.getValue()));
        });
        addAttachListener(event -> getElement().executeJs("return window.m3Theme.preference")
                .then(String.class, preference -> themeField.setValue(THEMES.contains(preference) ? preference : "system")));

        add(heading, intro,
                settingCard("Language", "Choose the language used for menus and controls.", language),
                settingCard("Theme", "Choose light or dark colors, or follow your device setting.", themeField));
    }

    private Div settingCard(String title, String description, ComboBox<?> field) {
        var card = new Div();
        card.addClassName("settings-card");
        var text = new Div();
        text.addClassName("settings-card-copy");
        var label = new Span(t(title));
        label.addClassName("settings-card-title");
        var help = new Span(t(description));
        help.addClassName("settings-card-description");
        text.add(label, help);
        var control = new Div(field);
        control.addClassName("settings-card-control");
        card.add(text, control);
        return card;
    }

    @Override public String getPageTitle() { return t("Settings"); }
}
