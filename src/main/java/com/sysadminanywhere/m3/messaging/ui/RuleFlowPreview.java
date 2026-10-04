package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.util.Comparator;

/** Read-only plan reflecting the worker stages, including filters before transformations. */
final class RuleFlowPreview extends VerticalLayout {
    RuleFlowPreview() { setPadding(false); setWidthFull(); addClassName("rule-flow"); }
    void show(Rule rule, ChannelSettings source, ChannelSettings destination) {
        removeAll(); add(new Span("Message flow"));
        var row = new HorizontalLayout(); row.setWrap(true); row.setAlignItems(Alignment.CENTER); row.setWidthFull();
        row.add(card("Source", source == null ? "Message from database / select source" : source.getName()));
        String conditions = "All messages";
        if (rule != null && !rule.getConditions().isEmpty()) {
            var text = new StringBuilder();
            for (var condition : rule.getConditions().stream().sorted(Comparator.comparing(RuleCondition::getId)).toList()) {
                if (!text.isEmpty()) text.append(" ").append(condition.getLogicalOperator()).append(" ");
                text.append(condition.getField()).append(" ").append(RuleConditionEditorDialog.operatorLabel(condition.getOperator()))
                        .append(" “").append(condition.getValue()).append("”");
            }
            conditions = text.toString();
        }
        next(row, card("Conditions · AND before OR", conditions));
        if (rule != null) {
            var actions = RuleActionEditorDialog.orderedActions(rule);
            actions.stream().filter(a -> a.getActionType() == ActionType.FILTER).findFirst()
                    .ifPresent(a -> next(row, card("Filter · first configured filter", RuleActionEditorDialog.description(a))));
            for (var action : actions) if (action.getActionType() != ActionType.FILTER)
                next(row, card(action.getPriority() + " · " + (action.getActionType() == ActionType.TRANSFORM ? "Transform body" : "Add metadata"),
                        RuleActionEditorDialog.description(action)));
        }
        next(row, card("Destination", destination == null ? "No forwarding" : destination.getName()));
        add(row);
    }
    private static void next(HorizontalLayout row, Div card) { row.add(new Span("→"), card); }
    private static Div card(String title, String text) {
        var heading = new Span(title); heading.addClassName("rule-flow-title");
        var description = new Span(text); var card = new Div(heading, description); card.addClassName("rule-flow-card"); return card;
    }
}
