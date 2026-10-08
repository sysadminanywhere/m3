package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Paged overview of channel, rule and worker assignments. User names remain verbatim. */
final class RuleMap extends VerticalLayout {
    private final RuleService rules;
    private final Div canvas = new Div();
    private final Span pageLabel = new Span();
    private int page;
    private final Button previous = new Button("←", e -> { page--; refresh(); });
    private final Button next = new Button("→", e -> { page++; refresh(); });
    RuleMap(RuleService rules) {
        this.rules = rules; setPadding(false); setWidthFull(); addClassName("rule-map");
        canvas.addClassName("rule-map-canvas");
        previous.setAriaLabel(t("Previous page")); next.setAriaLabel(t("Next page"));
        add(new Span(t("Select a rule block to edit its processing pipeline.")), canvas, new HorizontalLayout(previous, pageLabel, next));
        refresh();
    }
    void refresh() {
        var result = rules.page(PageRequest.of(Math.max(0, page), 20, Sort.by("priority", "id")));
        if (page > 0 && result.isEmpty()) { page = Math.max(0, result.getTotalPages() - 1); refresh(); return; }
        canvas.removeAll();
        for (var rule : result.getContent()) canvas.add(row(rule));
        if (result.isEmpty()) canvas.add(new Span(t("No rules configured")));
        previous.setEnabled(page > 0); next.setEnabled(result.hasNext());
        pageLabel.setText(t("Page {0} of {1}", page + 1, Math.max(1, result.getTotalPages())));
    }
    private Div row(Rule rule) {
        var sourceName = new Button(rule.getSourceChannel() == null ? t("Message from database / select source") : rule.getSourceChannel().getName(),
                e -> { if (rule.getSourceChannel() != null) getUI().ifPresent(ui -> ui.navigate("channel/" + rule.getSourceChannel().getId())); });
        sourceName.addThemeVariants(ButtonVariant.LUMO_TERTIARY); sourceName.addClassName("rule-map-link");
        sourceName.setEnabled(rule.getSourceChannel() != null);
        var source = new Div(new Span(t("Source")), sourceName);
        source.addClassNames("rule-map-endpoint", "rule-map-source");
        var edit = new Button(rule.getName(), e -> getUI().ifPresent(ui -> ui.navigate("rules/" + rule.getId())));
        edit.addThemeVariants(ButtonVariant.LUMO_PRIMARY); edit.addClassName("rule-map-rule-button");
        var pool = new Button(t("Worker pool") + ": " + (rule.getWorkerPool() == null ? t("Unassigned") : rule.getWorkerPool().getName()),
                e -> getUI().ifPresent(ui -> ui.navigate("workers")));
        pool.addThemeVariants(ButtonVariant.LUMO_TERTIARY); pool.addClassName("rule-map-link");
        var enabled = new Span(t(Boolean.TRUE.equals(rule.getEnabled()) ? "Enabled" : "Disabled"));
        var center = new Div(edit, pool, enabled); center.addClassName("rule-map-center");
        var destination = rule.getActions().stream().filter(action -> action.getDestinationChannel() != null
                && action.getDestinationChannel().getName().equals(rule.getDestinationChannelName())).map(action -> action.getDestinationChannel()).findFirst().orElse(null);
        var targetName = new Button(rule.getDestinationChannelName() == null ? t("No forwarding") : rule.getDestinationChannelName(),
                e -> { if (destination != null) getUI().ifPresent(ui -> ui.navigate("channel/" + destination.getId())); });
        targetName.addThemeVariants(ButtonVariant.LUMO_TERTIARY); targetName.addClassName("rule-map-link"); targetName.setEnabled(destination != null);
        var target = new Div(new Span(t("Destination")), targetName);
        target.addClassNames("rule-map-endpoint", "rule-map-target");
        var row = new Div(source, new Span("→"), center, new Span("→"), target); row.addClassName("rule-map-row"); return row;
    }
}
