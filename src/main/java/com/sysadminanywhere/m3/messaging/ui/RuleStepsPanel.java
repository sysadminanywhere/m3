package com.sysadminanywhere.m3.messaging.ui;

import static com.sysadminanywhere.m3.base.i18n.Translations.t;

import com.sysadminanywhere.m3.base.i18n.Translations;
import com.sysadminanywhere.m3.messaging.domain.ActionType;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.domain.RuleAction;
import com.sysadminanywhere.m3.messaging.domain.RuleCondition;
import com.sysadminanywhere.m3.messaging.service.RuleService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compact rule conditions and actions editor without a flow diagram. */
final class RuleStepsPanel extends VerticalLayout {
    private RuleService rules;
    private Runnable changed;

    RuleStepsPanel() {
        setPadding(false);
        setSpacing(true);
        setWidthFull();
        addClassName("rule-steps-panel");
    }

    void configure(RuleService rules, Runnable changed) {
        this.rules = rules;
        this.changed = changed;
    }

    void show(Rule rule) {
        removeAll();
        if (rule == null) return;

        var preview = new Button(t("Preview entire rule"), event -> new RuleSimulationDialog(rules, rule).open());
        preview.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        var title = new H3(t("Conditions and actions"));
        var top = new HorizontalLayout(title, preview);
        top.setWidthFull();
        top.setAlignItems(Alignment.CENTER);
        top.setJustifyContentMode(JustifyContentMode.BETWEEN);
        top.setWrap(true);
        add(top);

        var conditionRows = new VerticalLayout();
        conditionRows.setPadding(false);
        conditionRows.setSpacing(false);
        var conditions = rule.getConditions().stream().sorted(java.util.Comparator.comparing(RuleCondition::getId)).toList();
        if (conditions.isEmpty()) conditionRows.add(muted(t("All messages")));
        for (int index = 0; index < conditions.size(); index++) {
            var condition = conditions.get(index);
            if (index > 0) conditionRows.add(muted(Translations.enumLabel(condition.getLogicalOperator())));
            String summary = condition.getField() + " " + RuleConditionEditorDialog.operatorLabel(condition.getOperator())
                    + " “" + condition.getValue() + "”";
            var edit = new Button(summary, event -> new RuleConditionEditorDialog(rules, rule.getId(), condition, changed).open());
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            edit.addClassName("rule-step-description");
            edit.getElement().setAttribute("title", summary);
            var row = row(edit, iconButton(VaadinIcon.TRASH, t("Delete"),
                    () -> mutate(() -> rules.deleteCondition(condition.getId()))));
            conditionRows.add(row);
        }
        var conditionAdd = new Button(t("Add Condition"), event ->
                new RuleConditionEditorDialog(rules, rule.getId(), null, changed).open());
        conditionAdd.addThemeVariants(ButtonVariant.LUMO_SMALL);
        add(section(t("Conditions"), conditionRows, conditionAdd));

        var actions = RuleActionEditorDialog.orderedActions(rule);
        var filters = actions.stream().filter(action -> action.getActionType() == ActionType.FILTER).toList();
        var filterRows = new VerticalLayout();
        filterRows.setPadding(false);
        filterRows.setSpacing(false);
        if (filters.isEmpty()) filterRows.add(muted(t("Pass message")));
        for (int index = 0; index < filters.size(); index++) {
            var action = filters.get(index);
            String summary = RuleActionEditorDialog.description(action);
            var edit = description(action, summary, rule);
            var controls = new HorizontalLayout(edit, iconButton(VaadinIcon.TRASH, t("Delete"),
                    () -> mutate(() -> rules.deleteAction(action.getId()))));
            controls.setAlignItems(Alignment.CENTER);
            controls.setWidthFull();
            filterRows.add(row(controls));
            if (index > 0) filterRows.add(muted(t("Inactive: an earlier filter is used")));
        }
        var filterAdd = new Button(t("Add filter"), event -> addAction(rule, ActionType.FILTER));
        filterAdd.addThemeVariants(ButtonVariant.LUMO_SMALL);
        add(section(t("Filter message"), filterRows, filterAdd));

        var processingRows = new VerticalLayout();
        processingRows.setPadding(false);
        processingRows.setSpacing(false);
        var processing = actions.stream().filter(action -> action.getActionType() != ActionType.FILTER).toList();
        if (processing.isEmpty()) processingRows.add(muted(t("No transformations or metadata actions")));
        for (int index = 0; index < processing.size(); index++) {
            var action = processing.get(index);
            String summary = (index + 1) + ". " + t(action.getActionType() == ActionType.TRANSFORM
                    ? "Transform body" : "Add metadata") + " — " + RuleActionEditorDialog.description(action);
            var edit = description(action, summary, rule);
            var controls = new HorizontalLayout(edit,
                    moveButton(rule, processing, index, -1), moveButton(rule, processing, index, 1),
                    iconButton(VaadinIcon.TRASH, t("Delete"), () -> mutate(() -> rules.deleteAction(action.getId()))));
            controls.setWidthFull();
            controls.setAlignItems(Alignment.CENTER);
            controls.setWrap(true);
            processingRows.add(row(controls));
        }
        var addTransform = new Button(t("Add transformation"), event -> addAction(rule, ActionType.TRANSFORM));
        addTransform.addThemeVariants(ButtonVariant.LUMO_SMALL);
        var addMetadata = new Button(t("Add metadata"), event -> addAction(rule, ActionType.ENRICH));
        addMetadata.addThemeVariants(ButtonVariant.LUMO_SMALL);
        var addActions = new HorizontalLayout(addTransform, addMetadata);
        addActions.setWrap(true);
        add(section(t("Transform body / metadata"), processingRows, addActions));
    }

    private Div section(String title, VerticalLayout rows, com.vaadin.flow.component.Component controls) {
        var heading = new H3(title);
        heading.addClassName("rule-steps-heading");
        var section = new Div(heading, rows, controls);
        section.addClassName("rule-steps-section");
        return section;
    }

    private Div row(com.vaadin.flow.component.Component... components) {
        var row = new Div(components);
        row.addClassName("rule-step-row");
        return row;
    }

    private Span muted(String text) {
        var label = new Span(text);
        label.addClassName("rule-step-muted");
        return label;
    }

    private Button description(RuleAction action, String summary, Rule rule) {
        var edit = new Button(summary, event -> new RuleActionEditorDialog(rules, rule.getId(), action,
                action.getActionType(), changed).open());
        edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        edit.addClassName("rule-step-description");
        edit.getElement().setAttribute("title", summary);
        return edit;
    }

    private void addAction(Rule rule, ActionType type) {
        new RuleActionEditorDialog(rules, rule.getId(), null, type, changed).open();
    }

    private Button moveButton(Rule rule, List<RuleAction> actions, int index, int delta) {
        var button = iconButton(delta < 0 ? VaadinIcon.ARROW_UP : VaadinIcon.ARROW_DOWN,
                t(delta < 0 ? "Move up" : "Move down"), () -> {
                    var ids = new ArrayList<>(actions.stream().map(RuleAction::getId).toList());
                    Collections.swap(ids, index, index + delta);
                    mutate(() -> rules.reorderActions(rule.getId(), ids));
                });
        button.setEnabled(index + delta >= 0 && index + delta < actions.size());
        return button;
    }

    private void mutate(Runnable action) {
        try {
            action.run();
            changed.run();
        } catch (IllegalArgumentException error) {
            Notification.show(t(error.getMessage()), 5000, Notification.Position.BOTTOM_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private Button iconButton(VaadinIcon icon, String label, Runnable action) {
        var button = new Button(icon.create(), event -> action.run());
        button.setAriaLabel(label);
        button.setTooltipText(label);
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        return button;
    }
}
