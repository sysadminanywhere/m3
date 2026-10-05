package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;

import com.sysadminanywhere.m3.messaging.source.InboundSourceSpec;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import java.util.*;

final class ChannelExtraSettings extends VerticalLayout {
    private record PropertyRow(TextField key, TextArea value, HorizontalLayout layout) { }
    private final VerticalLayout propertyRows = new VerticalLayout();
    private final List<PropertyRow> rows = new ArrayList<>();
    private Set<String> visible = Set.of();

    ChannelExtraSettings() {
        setWidthFull(); setPadding(false); setSpacing(true);
        propertyRows.setWidthFull(); propertyRows.setPadding(false);
        var addProperty = new Button(t("Add property"), VaadinIcon.PLUS.create(), event -> addRow("", ""));
        addProperty.addThemeVariants(ButtonVariant.LUMO_SMALL);
        var hint = new Span(t("Additional protocol settings, for example kafka.security.protocol. Loading policy belongs to the rule."));
        hint.getStyle().set("white-space", "normal");
        add(new Span(t("Additional connection properties")), hint, propertyRows, addProperty);
    }
    private void addRow(String name, String content) {
        var key = new TextField(t("Key")); key.setMaxLength(255); key.setValue(name); key.setWidth("240px");
        var value = new TextArea(t("Value")); value.setValue(content); value.setMinHeight("80px");
        value.setWidthFull();
        var remove = new Button(VaadinIcon.TRASH.create());
        remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        remove.setAriaLabel(t("Remove connection property"));
        var layout = new HorizontalLayout(key, value, remove);
        layout.setWidthFull(); layout.setWrap(true); layout.setAlignItems(Alignment.END);
        layout.setFlexGrow(1, value);
        var row = new PropertyRow(key, value, layout);
        remove.addClickListener(event -> { rows.remove(row); propertyRows.remove(layout); });
        rows.add(row); propertyRows.add(layout);
    }
    void load(Map<String,String> properties, Set<String> visible) {
        this.visible = Set.copyOf(visible);
        rows.clear(); propertyRows.removeAll();
        var extra = new TreeMap<>(properties);
        visible.forEach(extra::remove); InboundSourceSpec.LOADING_KEYS.forEach(extra::remove);
        extra.remove("keyDeserializer"); extra.remove("valueDeserializer");
        extra.forEach(this::addRow);
    }
    Map<String,String> settings() {
        var result = new HashMap<String,String>();
        for (var row : rows) {
            String key = row.key().getValue().trim();
            String value = row.value().getValue();
            row.key().setInvalid(false);
            if (key.isEmpty() && value.isEmpty()) continue;
            if (key.isEmpty() || key.length() > 255) fail(row, t("Enter a property key (1–255 characters)"));
            if (visible.contains(key) || InboundSourceSpec.LOADING_KEYS.contains(key)
                    || Set.of("keyDeserializer", "valueDeserializer").contains(key))
                fail(row, t("Use the dedicated field or rule settings for: ") + key);
            if (result.containsKey(key)) fail(row, t("Duplicate connection property: ") + key);
            result.put(key, value);
        }
        return result;
    }
    private static void fail(PropertyRow row, String message) {
        row.key().setInvalid(true); row.key().setErrorMessage(message);
        throw new IllegalArgumentException(message);
    }
}
