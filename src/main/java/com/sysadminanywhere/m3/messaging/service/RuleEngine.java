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

        conditions.sort(Comparator.comparing(c -> c.getLogicalOperator() == LogicalOperator.AND ? 0 : 1));

        boolean result = true;
        LogicalOperator currentOp = LogicalOperator.AND;

        for (RuleCondition condition : conditions) {
            boolean conditionResult = evaluateCondition(condition, springMessage);
            
            if (currentOp == LogicalOperator.AND) {
                result = result && conditionResult;
            } else {
                result = result || conditionResult;
            }

            currentOp = condition.getLogicalOperator();
        }

        return result;
    }

    private boolean evaluateCondition(RuleCondition condition, Message<?> springMessage) {
        Object fieldValue = getFieldValue(condition.getField(), springMessage);
        String conditionValue = condition.getValue();

        if (fieldValue == null) {
            return conditionValue == null || conditionValue.isEmpty();
        }

        String fieldValueStr = fieldValue.toString();

        return switch (condition.getOperator()) {
            case EQUALS -> fieldValueStr.equals(conditionValue);
            case NOT_EQUALS -> !fieldValueStr.equals(conditionValue);
            case CONTAINS -> fieldValueStr.contains(conditionValue);
            case GREATER -> compareNumbers(fieldValueStr, conditionValue) > 0;
            case LESS -> compareNumbers(fieldValueStr, conditionValue) < 0;
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

    private int compareNumbers(String a, String b) {
        try {
            double numA = Double.parseDouble(a);
            double numB = Double.parseDouble(b);
            return Double.compare(numA, numB);
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
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
        if (payload instanceof Map && script.startsWith("$.")) {
            String jsonPath = script.substring(2);
            Map<?, ?> map = (Map<?, ?>) payload;
            return map.get(jsonPath);
        }
        return payload;
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
