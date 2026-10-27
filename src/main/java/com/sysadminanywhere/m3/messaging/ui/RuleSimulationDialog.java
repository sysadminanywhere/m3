package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.service.RuleEngine;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.sysadminanywhere.m3.base.i18n.Translations;
import org.springframework.messaging.support.GenericMessage;
import java.util.*;

/** In-memory preview of saved conditions/actions using the actual worker rule engine. */
final class RuleSimulationDialog extends Dialog {
    private record HeaderRow(TextField key, TextField value) { }
    RuleSimulationDialog(RuleService rules, Rule rule) {
        setHeaderTitle(t("Preview entire rule")); setWidth("760px"); setMaxWidth("calc(100vw - 32px)");
        var format = new ComboBox<String>(t("Example format")); format.setItems("Text", "JSON");
        format.setItemLabelGenerator(Translations::t); format.setValue("JSON");
        var input = new TextArea(t("Input example")); input.setWidthFull(); input.setMaxLength(10000);
        input.setValue("{\"body\":\"  Hello M3  \"}"); input.setMinHeight("120px");
        var headers = new VerticalLayout(); headers.setPadding(false); var rows = new ArrayList<HeaderRow>();
        var addHeader = new Button(t("Add metadata"), e -> {
            var key = new TextField(t("Key")); key.setMaxLength(100); key.setRequired(true);
            var value = new TextField(t("Value")); value.setMaxLength(1000);
            var fields = new HorizontalLayout(key, value); fields.setWidthFull(); fields.setWrap(true); value.setWidth("300px");
            var row = new HeaderRow(key, value); rows.add(row);
            fields.add(new Button(t("Delete"), ignored -> { rows.remove(row); headers.remove(fields); })); headers.add(fields);
        });
        var status = new Span(); status.addClassName("rule-simulation-status");
        var output = new TextArea(t("Result")); output.setWidthFull(); output.setReadOnly(true); output.setMinHeight("120px");
        var outputMetadata = new TextArea(t("Metadata")); outputMetadata.setWidthFull(); outputMetadata.setReadOnly(true);
        var steps = new VerticalLayout(); steps.setPadding(false); steps.setWidthFull();
        var run = new Button(t("Run preview"), e -> {
            output.clear(); outputMetadata.clear(); steps.removeAll();
            try {
                var json = new ObjectMapper();
                Object payload = "JSON".equals(format.getValue()) ? json.readValue(input.getValue(), Object.class) : input.getValue();
                if (payload == null) throw new IllegalArgumentException("Invalid example");
                var metadata = new LinkedHashMap<String, Object>();
                for (var row : rows) {
                    if (row.key().getValue().isBlank()) throw new IllegalArgumentException("Metadata key and value are required");
                    if (Set.of("id", "timestamp").contains(row.key().getValue()) || metadata.putIfAbsent(row.key().getValue(), row.value().getValue()) != null)
                        throw new IllegalArgumentException("Invalid or duplicate preview metadata key");
                }
                var engine = new RuleEngine(rules); var message = new GenericMessage<>(payload, metadata);
                if (!engine.evaluateConditions(rule, message)) { status.setText(t("Conditions did not match; the message is not handled by this rule.")); return; }
                if (engine.shouldFilter(message, List.of(rule))) { status.setText(t("Message discarded; subsequent blocks are not executed")); return; }
                boolean idMode = rule.getOutboundPayloadMode() == com.sysadminanywhere.m3.messaging.domain.OutboundPayloadMode.MESSAGE_ID
                        && (rule.getRuleType() == com.sysadminanywhere.m3.messaging.domain.RuleType.OUTBOUND
                        || rule.getActions().stream().anyMatch(action -> action.getActionType() == com.sysadminanywhere.m3.messaging.domain.ActionType.ROUTE));
                java.util.function.BiConsumer<com.sysadminanywhere.m3.messaging.domain.RuleAction, org.springframework.messaging.Message<?>> observer = (action, step) -> {
                    var value = new TextArea(RuleActionEditorDialog.description(action));
                    value.setWidthFull(); value.setReadOnly(true);
                    try {
                        var display = new LinkedHashMap<String, Object>();
                        display.put(t("Payload"), step.getPayload());
                        var stepHeaders = new TreeMap<>(step.getHeaders()); stepHeaders.remove("id"); stepHeaders.remove("timestamp");
                        display.put(t("Metadata"), stepHeaders);
                        value.setValue(json.writerWithDefaultPrettyPrinter().writeValueAsString(display));
                    } catch (Exception invalid) { value.setValue(t("Invalid example")); }
                    steps.add(value);
                };
                var result = idMode ? engine.applyMetadataActions(message, List.of(rule), observer)
                        : engine.applyTransformations(message, List.of(rule), observer);
                output.setValue(result.getPayload() instanceof String text ? text : json.writerWithDefaultPrettyPrinter().writeValueAsString(result.getPayload()));
                var resultHeaders = new TreeMap<>(result.getHeaders()); resultHeaders.remove("id"); resultHeaders.remove("timestamp");
                outputMetadata.setValue(json.writerWithDefaultPrettyPrinter().writeValueAsString(resultHeaders));
                status.setText(t(idMode
                        ? "ID delivery: body transformations are skipped. The result shows the original example; the receiver gets the stored message ID. Nothing was sent."
                        : "Conditions matched; all transformations completed. Nothing was sent."));
            } catch (Exception error) { status.setText(t(error.getMessage() == null ? "Invalid example" : error.getMessage())); }
        });
        run.addThemeVariants(ButtonVariant.PRIMARY);
        var content = new VerticalLayout(new Span(t("Try a message and its metadata against the saved rule. Preview does not write to the database or send to channels.")),
                format, input, addHeader, headers, run, status, steps, output, outputMetadata); content.setPadding(false); add(content);
        getFooter().add(new Button(t("Close"), e -> close()));
    }

    static org.springframework.messaging.Message<?> previewActions(RuleEngine engine, Rule rule,
            org.springframework.messaging.Message<?> message, boolean idMode) {
        return idMode ? engine.applyMetadataActions(message, List.of(rule)) : engine.applyTransformations(message, List.of(rule));
    }
}
