package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final RuleService ruleService;

    public RuleEngine(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    public List<Rule> findApplicableRules(Message<?> springMessage, String channelName) {
        List<Rule> allRules = ruleService.findEnabledBySourceChannelName(channelName);
        List<Rule> applicableRules = new ArrayList<>();

        for (Rule rule : allRules) {
            if (evaluateConditions(rule, springMessage)) {
                applicableRules.add(rule);
            }
        }

        applicableRules.sort(Comparator.comparing(Rule::getPriority));
        return applicableRules;
    }

    public boolean evaluateConditions(Rule rule, Message<?> springMessage) {
        List<RuleCondition> conditions = new ArrayList<>(rule.getConditions());
        if (conditions.isEmpty()) {
            return true;
        }

        conditions.sort(Comparator.comparing(RuleCondition::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        boolean group = evaluateCondition(conditions.getFirst(), springMessage);
        boolean result = false;
        for (int index = 1; index < conditions.size(); index++) {
            var condition = conditions.get(index);
            boolean matches = evaluateCondition(condition, springMessage);
            if (condition.getLogicalOperator() == LogicalOperator.OR) {
                result |= group;
                group = matches;
            } else group &= matches;
        }
        return result || group;
    }

    private boolean evaluateCondition(RuleCondition condition, Message<?> springMessage) {
        Object fieldValue = getFieldValue(condition.getField(), springMessage);
        String conditionValue = condition.getValue();

        if (fieldValue == null) {
            return false;
        }

        String fieldValueStr = fieldValue.toString();

        return switch (condition.getOperator()) {
            case EQUALS -> fieldValueStr.equals(conditionValue);
            case NOT_EQUALS -> !fieldValueStr.equals(conditionValue);
            case CONTAINS -> fieldValueStr.contains(conditionValue);
            case GREATER -> numericMatches(fieldValueStr, conditionValue, true);
            case LESS -> numericMatches(fieldValueStr, conditionValue, false);
            case REGEX -> {
                try {
                    yield Pattern.compile(conditionValue).matcher(fieldValueStr).find();
                } catch (PatternSyntaxException e) {
                    log.error("Invalid regex pattern: {}", conditionValue, e);
                    yield false;
                }
            }
        };
    }

    private Object getFieldValue(String field, Message<?> springMessage) {
        if (field.startsWith("header.")) {
            String headerName = field.substring(7);
            return springMessage.getHeaders().get(headerName);
        } else if (field.startsWith("payload.")) {
            String payloadField = field.substring(8);
            Object payload = springMessage.getPayload();
            if (payload instanceof Map) {
                return ((Map<?, ?>) payload).get(payloadField);
            }
            return null;
        } else if (field.equals("payload")) {
            return springMessage.getPayload();
        }
        return null;
    }

    private boolean numericMatches(String a, String b, boolean greater) {
        try {
            int comparison = new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b));
            return greater ? comparison > 0 : comparison < 0;
        } catch (NumberFormatException invalid) { return false; }
    }

    public String determineTargetChannel(Message<?> springMessage, List<Rule> applicableRules) {
        for (Rule rule : applicableRules) {
            for (RuleAction action : orderedActions(rule)) {
                if (action.getActionType() == ActionType.ROUTE && action.getTargetChannel() != null) {
                    return action.getTargetChannel();
                }
            }
        }
        return null;
    }

    public Message<?> applyTransformations(Message<?> springMessage, List<Rule> applicableRules) {
        Object payload = springMessage.getPayload();
        Map<String, Object> headers = new HashMap<>(springMessage.getHeaders());

        for (Rule rule : applicableRules) {
            for (RuleAction action : orderedActions(rule)) {
                if (action.getActionType() == ActionType.TRANSFORM && action.getTransformationScript() != null) {
                    payload = applyTransformation(payload, action.getTransformationScript());
                } else if (action.getActionType() == ActionType.ENRICH) {
                    if (action.getMetadataKey() != null && action.getMetadataValue() != null) {
                        headers.put(action.getMetadataKey(), action.getMetadataValue());
                    }
                }
            }
        }

        return new org.springframework.messaging.support.GenericMessage<>(payload, headers);
    }

    private Object applyTransformation(Object payload, String script) {
        return RuleTransformations.apply(payload, script);
    }

    public static void validateTransformation(String script) {
        RuleTransformations.parse(script);
    }

    public boolean shouldFilter(Message<?> springMessage, List<Rule> applicableRules) {
        for (Rule rule : applicableRules) {
            for (RuleAction action : orderedActions(rule)) {
                if (action.getActionType() == ActionType.FILTER && action.getFilterResult() != null) {
                    return !action.getFilterResult();
                }
            }
        }
        return false;
    }

    private static List<RuleAction> orderedActions(Rule rule) {
        return rule.getActions().stream().sorted(Comparator.comparing(RuleAction::getPriority)
                .thenComparing(RuleAction::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }
}
