package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;
import com.sysadminanywhere.m3.base.i18n.Translations;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.details.Details;
import java.util.*;

/** Editable pipeline. Stage order matches the worker: conditions, filter, body/metadata, delivery. */
final class RuleFlowPreview extends VerticalLayout {
    private RuleService rules;
    private Runnable changed;
    private Component sourceField, destinationField, loadingField, workerField;
    RuleFlowPreview() { setPadding(false); setWidthFull(); addClassName("rule-designer"); }
    void configure(RuleService rules, Runnable changed, Component sourceField, Component destinationField,
                   Component loadingField, Component workerField) {
        this.rules = rules; this.changed = changed; this.sourceField = sourceField;
        this.destinationField = destinationField; this.loadingField = loadingField; this.workerField = workerField;
    }
    void show(Rule rule, ChannelSettings source, ChannelSettings destination) {
        removeAll();
        var title = new Span(t("Visual rule editor")); title.addClassName("rule-designer-heading");
        var preview = new Button(t("Preview entire rule"), VaadinIcon.PLAY.create(), e -> new RuleSimulationDialog(rules, rule).open());
        preview.setEnabled(rule != null); preview.addThemeVariants(ButtonVariant.LUMO_SMALL);
        var heading = new HorizontalLayout(title, preview); heading.setWidthFull(); heading.setJustifyContentMode(JustifyContentMode.BETWEEN);
        heading.setWrap(true); add(heading, new Span(t("Edit blocks directly. Changes to conditions and actions are saved immediately; save the rule to apply channel settings.")));
        if (rules == null) return;
        boolean saved = rule != null;
        var canvas = new Div(); canvas.addClassName("rule-designer-canvas");
        var input = node("source", VaadinIcon.DOWNLOAD, t("Source"), source == null ? t("Message from database / select source") : source.getName());
        if (sourceField != null) input.add(sourceField);
        if (loadingField != null && loadingField.isVisible()) input.add(new Details(t("Loading settings"), loadingField));
        if (workerField != null) input.add(workerField);
        canvas.add(input); connect(canvas);
        var conditions = node("conditions", VaadinIcon.FILTER, t("Conditions"), t("Conditions · AND before OR"));
        if (saved && !rule.getConditions().isEmpty()) {
            boolean first = true;
            for (var condition : rule.getConditions().stream().sorted(Comparator.comparing(RuleCondition::getId)).toList()) {
                if (!first) { var connector = new Span(Translations.enumLabel(condition.getLogicalOperator())); connector.addClassName("rule-logic-chip"); conditions.add(connector); }
                first = false;
                var edit = new Button(condition.getField() + " " + RuleConditionEditorDialog.operatorLabel(condition.getOperator()) + " “" + condition.getValue() + "”",
                        e -> new RuleConditionEditorDialog(rules, rule.getId(), condition, changed).open());
                edit.addClassName("rule-condition-button"); edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                conditions.add(new HorizontalLayout(edit, iconButton(VaadinIcon.TRASH, t("Delete"), () -> mutate(() -> rules.deleteCondition(condition.getId())))));
            }
        } else conditions.add(new Span(t("All messages")));
        var addCondition = new Button(t("Add Condition"), e -> new RuleConditionEditorDialog(rules, rule.getId(), null, changed).open());
        addCondition.setEnabled(saved); conditions.add(addCondition);
        var branch = new Span(t("No match → this rule does not handle the message")); branch.addClassName("rule-branch-note"); conditions.add(branch);
        canvas.add(conditions); connect(canvas);
        var actions = saved ? RuleActionEditorDialog.orderedActions(rule) : List.<RuleAction>of();
        var filters = actions.stream().filter(a -> a.getActionType() == ActionType.FILTER).toList();
        var filtering = node("filter", VaadinIcon.BAN, t("Filter message"), filters.isEmpty() ? t("Pass message") : t("Filter · first configured filter"));
        for (int index = 0; index < filters.size(); index++) {
            var action = filters.get(index);
            var row = new HorizontalLayout(new Button(RuleActionEditorDialog.description(action), e -> edit(rule, action)),
                    iconButton(VaadinIcon.TRASH, t("Delete"), () -> mutate(() -> rules.deleteAction(action.getId()))));
            if (index > 0) row.add(new Span(t("Inactive: an earlier filter is used")));
            row.setWrap(true); filtering.add(row);
        }
        if (filters.isEmpty()) filtering.add(addAction(rule, ActionType.FILTER, t("Add filter")));
        canvas.add(filtering);
        boolean discarded = !filters.isEmpty() && !Boolean.TRUE.equals(filters.getFirst().getFilterResult());
        if (discarded) { var stop = new Span(t("Message discarded; subsequent blocks are not executed")); stop.addClassName("rule-branch-note"); filtering.add(stop); }
        var processing = actions.stream().filter(a -> a.getActionType() != ActionType.FILTER).toList();
        for (int index = 0; index < processing.size(); index++) {
            connect(canvas);
            var action = processing.get(index);
            var block = node(action.getActionType() == ActionType.TRANSFORM ? "transform" : "metadata",
                    action.getActionType() == ActionType.TRANSFORM ? VaadinIcon.MAGIC : VaadinIcon.TAGS,
                    (index + 1) + " · " + t(action.getActionType() == ActionType.TRANSFORM ? "Transform body" : "Add metadata"), RuleActionEditorDialog.description(action));
            var tools = new HorizontalLayout(new Button(t("Edit"), e -> edit(rule, action)),
                    moveButton(rule, processing, index, -1), moveButton(rule, processing, index, 1),
                    iconButton(VaadinIcon.TRASH, t("Delete"), () -> mutate(() -> rules.deleteAction(action.getId()))));
            tools.setWrap(true); block.add(tools);
            if (discarded) block.addClassName("rule-node-skipped");
            canvas.add(block);
        }
        connect(canvas);
        var insert = new HorizontalLayout(addAction(rule, ActionType.TRANSFORM, t("Add transformation")), addAction(rule, ActionType.ENRICH, t("Add metadata")));
        insert.setWrap(true); insert.addClassName("rule-insert-actions"); canvas.add(insert); connect(canvas);
        var output = node("destination", VaadinIcon.UPLOAD, t("Destination"), destination == null ? t("No forwarding") : destination.getName());
        if (destinationField != null) output.add(destinationField);
        output.add(new Span(t("Original message remains in the database; delivery uses a separate copy.")));
        if (discarded) output.addClassName("rule-node-skipped");
        canvas.add(output); add(canvas);
        if (!saved) add(new Span(t("Please save the rule first")));
    }
    private Button addAction(Rule rule, ActionType type, String label) {
        var button = new Button(label, e -> new RuleActionEditorDialog(rules, rule.getId(), null, type, changed).open());
        button.setEnabled(rule != null); return button;
    }
    private void edit(Rule rule, RuleAction action) { new RuleActionEditorDialog(rules, rule.getId(), action, changed).open(); }
    private Button moveButton(Rule rule, List<RuleAction> actions, int index, int delta) {
        var button = iconButton(delta < 0 ? VaadinIcon.ARROW_UP : VaadinIcon.ARROW_DOWN, t(delta < 0 ? "Move up" : "Move down"), () -> {
            var ids = new ArrayList<>(actions.stream().map(RuleAction::getId).toList());
            Collections.swap(ids, index, index + delta); mutate(() -> rules.reorderActions(rule.getId(), ids));
        });
        button.setEnabled(index + delta >= 0 && index + delta < actions.size()); return button;
    }
    private void mutate(Runnable action) {
        try { action.run(); changed.run(); }
        catch (IllegalArgumentException error) { Notification.show(t(error.getMessage()), 5000, Notification.Position.BOTTOM_END).addThemeVariants(NotificationVariant.LUMO_ERROR); }
    }
    private static Button iconButton(VaadinIcon icon, String label, Runnable action) {
        var button = new Button(icon.create(), e -> action.run()); button.setAriaLabel(label); button.setTooltipText(label);
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY); return button;
    }
    private static void connect(Div canvas) { var arrow = new Div(new Span("↓")); arrow.addClassName("rule-wire"); canvas.add(arrow); }
    private static Div node(String kind, VaadinIcon icon, String title, String description) {
        var heading = new HorizontalLayout(icon.create(), new Span(title)); heading.addClassName("rule-node-title");
        var detail = new Span(description); detail.addClassName("rule-node-description");
        var node = new Div(heading, detail); node.addClassNames("rule-node", "rule-node-" + kind); return node;
    }
}
