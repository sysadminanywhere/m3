package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.sysadminanywhere.m3.base.i18n.Translations;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import com.sysadminanywhere.m3.messaging.service.MessageSearch;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.QueryParameters;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;

final class MessageFilterBar extends Div {
    final ComboBox<MessageStatus> status = new ComboBox<>(t("Status"));
    private final TextField id = new TextField("ID");
    private final TextField source = new TextField(t("Source"));
    private final TextField target = new TextField(t("Target"));
    private final TextField text = new TextField(t("Payload contains"));
    private final TextField correlation = new TextField(t("Correlation ID"));
    private final DatePicker from = new DatePicker(t("From date"));
    private final DatePicker to = new DatePicker(t("To date"));
    private final Span error = new Span();
    private final String route;
    private MessageSearch active = MessageSearch.empty();
    private boolean valid = true;

    MessageFilterBar(String route, Runnable refresh) {
        this.route = route;
        addClassName("message-filters");
        status.setItems(MessageStatus.values());
        status.setItemLabelGenerator(Translations::enumLabel);
        status.setClearButtonVisible(true);
        id.setMaxLength(19); id.setAllowedCharPattern("[0-9]"); id.setClearButtonVisible(true);
        for (var field : List.of(source, target,text,correlation)) { field.setMaxLength(200); field.setClearButtonVisible(true); }
        text.setHelperText(t("Searches masked hot payloads; indexing is asynchronous."));
        from.setClearButtonVisible(true); to.setClearButtonVisible(true);
        from.setLocale(Translations.locale()); to.setLocale(Translations.locale());
        var fields = new Div(id, status, source, target,text,correlation, from, to);
        fields.addClassName("message-filter-fields");
        var apply = new Button(t("Apply filters"), event -> apply());
        apply.addThemeVariants(ButtonVariant.PRIMARY);
        var reset = new Button(t("Reset filters"), event -> getUI().ifPresent(ui -> ui.navigate(route)));
        var reload = new Button(t("Refresh"), event -> refresh.run());
        var timezone = new Span(t("Dates use time zone: {0}", ZoneId.systemDefault().getId()));
        timezone.addClassName("filter-timezone");
        var actions = new Div(apply, reset, reload, timezone);
        actions.addClassName("message-filter-actions");
        error.addClassName("form-error"); error.getElement().setAttribute("role", "alert"); error.setVisible(false);
        add(fields, actions, error);
        for (var field : List.of(id, source, target,text,correlation)) field.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, event -> apply());
    }

    void load(QueryParameters query) {
        valid = true; error.setVisible(false);
        try {
            active = MessageSearch.fromParameters(query.getParameters());
            id.setValue(active.id() == null ? "" : active.id().toString());
            status.setValue(active.status()); source.setValue(active.source()); target.setValue(active.target());
            text.setValue(active.text()); correlation.setValue(active.correlation());
            from.setValue(active.from()); to.setValue(active.to());
        } catch (IllegalArgumentException invalid) {
            active = MessageSearch.empty(); valid = false;
            showError(invalid.getMessage());
        }
    }

    private void apply() {
        if (from.isInvalid() || to.isInvalid()) { showError("Invalid date filter"); return; }
        var params = new LinkedHashMap<String, List<String>>();
        params.put("id", List.of(id.getValue()));
        params.put("status", List.of(status.getValue() == null ? "" : status.getValue().name()));
        params.put("source", List.of(source.getValue())); params.put("target", List.of(target.getValue()));
        params.put("text",List.of(text.getValue())); params.put("correlation",List.of(correlation.getValue()));
        params.put("from", List.of(from.getValue() == null ? "" : from.getValue().toString()));
        params.put("to", List.of(to.getValue() == null ? "" : to.getValue().toString()));
        try {
            var search = MessageSearch.fromParameters(params);
            getUI().ifPresent(ui -> ui.navigate(route, QueryParameters.simple(search.parameters())));
        } catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); }
    }

    private void showError(String text) { error.setText(t(text)); error.setVisible(true); }
    MessageSearch active() { return active; }
    boolean valid() { return valid; }
}
