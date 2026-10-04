package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.support.GenericMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleEngineTest {

    @Mock
    private RuleService ruleService;

    @InjectMocks
    private RuleEngine ruleEngine;

    private ChannelSettings sourceChannel;
    private org.springframework.messaging.Message<?> springMessage;

    @BeforeEach
    void setUp() {
        sourceChannel = new ChannelSettings("test-channel", ChannelType.DIRECTORY, ChannelDirection.INBOUND);

        Map<String, Object> headers = new HashMap<>();
        headers.put("contentType", "application/json");
        headers.put("fileName", "test.xml");
        springMessage = new GenericMessage<>("{\"key\": \"value\"}", headers);
    }

    @Test
    void findApplicableRules_WhenNoRulesExist_ShouldReturnEmptyList() {
        // Given
        when(ruleService.findEnabledBySourceChannelName("test-channel")).thenReturn(List.of());

        // When
        List<Rule> result = ruleEngine.findApplicableRules(springMessage, "test-channel");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void findApplicableRules_WhenRuleHasNoConditions_ShouldReturnRule() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        when(ruleService.findEnabledBySourceChannelName("test-channel")).thenReturn(List.of(rule));

        // When
        List<Rule> result = ruleEngine.findApplicableRules(springMessage, "test-channel");

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("rule");
    }

    @Test
    void findApplicableRules_WhenRuleMatchesConditions_ShouldReturnRule() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.contentType", ConditionOperator.EQUALS, "application/json");
        condition.setLogicalOperator(LogicalOperator.AND);
        rule.getConditions().add(condition);

        when(ruleService.findEnabledBySourceChannelName("test-channel")).thenReturn(List.of(rule));

        // When
        List<Rule> result = ruleEngine.findApplicableRules(springMessage, "test-channel");

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findApplicableRules_WhenRuleDoesNotMatchConditions_ShouldNotReturnRule() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.contentType", ConditionOperator.EQUALS, "text/plain");
        condition.setLogicalOperator(LogicalOperator.AND);
        rule.getConditions().add(condition);

        when(ruleService.findEnabledBySourceChannelName("test-channel")).thenReturn(List.of(rule));

        // When
        List<Rule> result = ruleEngine.findApplicableRules(springMessage, "test-channel");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void findApplicableRules_ShouldReturnRulesSortedByPriority() {
        // Given
        Rule rule1 = new Rule("rule1", RuleType.INBOUND, sourceChannel);
        rule1.setPriority(10);
        Rule rule2 = new Rule("rule2", RuleType.INBOUND, sourceChannel);
        rule2.setPriority(5);

        when(ruleService.findEnabledBySourceChannelName("test-channel")).thenReturn(List.of(rule1, rule2));

        // When
        List<Rule> result = ruleEngine.findApplicableRules(springMessage, "test-channel");

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPriority()).isEqualTo(5);
        assertThat(result.get(1).getPriority()).isEqualTo(10);
    }

    @Test
    void evaluateConditions_WithEmptyConditions_ShouldReturnTrue() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithSingleMatchingCondition_ShouldReturnTrue() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.contentType", ConditionOperator.EQUALS, "application/json");
        condition.setLogicalOperator(LogicalOperator.AND);
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithMultipleAndConditions_AllMatch_ShouldReturnTrue() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition1 = new RuleCondition(rule, "header.contentType", ConditionOperator.EQUALS, "application/json");
        condition1.setLogicalOperator(LogicalOperator.AND);
        RuleCondition condition2 = new RuleCondition(rule, "header.fileName", ConditionOperator.CONTAINS, "test");
        condition2.setLogicalOperator(LogicalOperator.AND);
        rule.getConditions().add(condition1);
        rule.getConditions().add(condition2);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithOrCondition_SecondMatches_ShouldReturnTrue() {
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);

        RuleCondition condition1 = new RuleCondition(rule, "header.fileName", ConditionOperator.EQUALS, "nonexistent.xml");
        condition1.setLogicalOperator(LogicalOperator.AND); // Default, but explicit for clarity

        RuleCondition condition2 = new RuleCondition(rule, "header.contentType", ConditionOperator.EQUALS, "application/json");
        condition2.setLogicalOperator(LogicalOperator.OR);

        rule.getConditions().add(condition1);
        rule.getConditions().add(condition2);

        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithOrCondition_AfterAndSucceeds_ShouldReturnTrue() {
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);

        RuleCondition condition1 = new RuleCondition(rule, "header.contentType", ConditionOperator.EQUALS, "application/json");
        condition1.setLogicalOperator(LogicalOperator.AND);

        RuleCondition condition2 = new RuleCondition(rule, "header.fileName", ConditionOperator.EQUALS, "nonexistent.xml");
        condition2.setLogicalOperator(LogicalOperator.OR);

        rule.getConditions().add(condition1);
        rule.getConditions().add(condition2);

        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithContainsOperator_ShouldMatchSubstring() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.fileName", ConditionOperator.CONTAINS, ".xml");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithNotEqualsOperator_ShouldReturnTrueWhenDifferent() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.contentType", ConditionOperator.NOT_EQUALS, "text/plain");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, springMessage);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithRegexOperator_ShouldMatchPattern() {
        // Given
        Map<String, Object> headers = new HashMap<>();
        headers.put("fileName", "report_2024.pdf");
        org.springframework.messaging.Message<?> message = new GenericMessage<>("payload", headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.fileName", ConditionOperator.REGEX, ".*\\.pdf$");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, message);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithGreaterOperator_ShouldCompareNumbers() {
        // Given
        Map<String, Object> headers = new HashMap<>();
        headers.put("size", "100");
        org.springframework.messaging.Message<?> message = new GenericMessage<>("payload", headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.size", ConditionOperator.GREATER, "50");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, message);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateConditions_WithLessOperator_ShouldCompareNumbers() {
        // Given
        Map<String, Object> headers = new HashMap<>();
        headers.put("size", "25");
        org.springframework.messaging.Message<?> message = new GenericMessage<>("payload", headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.size", ConditionOperator.LESS, "50");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, message);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void determineTargetChannel_WithRouteAction_ShouldReturnTargetChannel() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.ROUTE);
        action.setTargetChannel("target-channel");
        rule.getActions().add(action);

        // When
        String result = ruleEngine.determineTargetChannel(springMessage, List.of(rule));

        // Then
        assertThat(result).isEqualTo("target-channel");
    }

    @Test
    void determineTargetChannel_WithNoRouteAction_ShouldReturnNull() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.TRANSFORM);
        rule.getActions().add(action);

        // When
        String result = ruleEngine.determineTargetChannel(springMessage, List.of(rule));

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldFilter_WithFilterActionReturningFalse_ShouldReturnTrue() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.FILTER);
        action.setFilterResult(false);
        rule.getActions().add(action);

        // When
        boolean result = ruleEngine.shouldFilter(springMessage, List.of(rule));

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void shouldFilter_WithFilterActionReturningTrue_ShouldReturnFalse() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.FILTER);
        action.setFilterResult(true);
        rule.getActions().add(action);

        // When
        boolean result = ruleEngine.shouldFilter(springMessage, List.of(rule));

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void shouldFilter_WithNoFilterAction_ShouldReturnFalse() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.ROUTE);
        rule.getActions().add(action);

        // When
        boolean result = ruleEngine.shouldFilter(springMessage, List.of(rule));

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void applyTransformations_WithEnrichAction_ShouldAddHeader() {
        // Given
        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.ENRICH);
        action.setMetadataKey("X-Processed");
        action.setMetadataValue("true");
        rule.getActions().add(action);

        // When
        org.springframework.messaging.Message<?> result = ruleEngine.applyTransformations(springMessage, List.of(rule));

        // Then
        assertThat(result.getHeaders().get("X-Processed")).isEqualTo("true");
    }

    @Test
    void applyTransformations_WithTransformAction_ShouldTransformPayload() {
        // Given
        Map<String, String> payload = new HashMap<>();
        payload.put("name", "test");
        payload.put("value", "123");

        Map<String, Object> headers = new HashMap<>();
        org.springframework.messaging.Message<?> message = new GenericMessage<>(payload, headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleAction action = new RuleAction(rule, ActionType.TRANSFORM);
        action.setTransformationScript("$.name");
        rule.getActions().add(action);

        // When
        org.springframework.messaging.Message<?> result = ruleEngine.applyTransformations(message, List.of(rule));

        // Then
        assertThat(result.getPayload()).isEqualTo("test");
    }

    @Test
    void applyTransformations_WithMultipleActions_ShouldApplyAll() {
        // Given
        Map<String, String> payload = new HashMap<>();
        payload.put("data", "content");

        Map<String, Object> headers = new HashMap<>();
        org.springframework.messaging.Message<?> message = new GenericMessage<>(payload, headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);

        RuleAction enrichAction = new RuleAction(rule, ActionType.ENRICH);
        enrichAction.setMetadataKey("X-Rule");
        enrichAction.setMetadataValue("applied");
        rule.getActions().add(enrichAction);

        RuleAction transformAction = new RuleAction(rule, ActionType.TRANSFORM);
        transformAction.setTransformationScript("$.data");
        rule.getActions().add(transformAction);

        // When
        org.springframework.messaging.Message<?> result = ruleEngine.applyTransformations(message, List.of(rule));

        // Then
        assertThat(result.getHeaders().get("X-Rule")).isEqualTo("applied");
        assertThat(result.getPayload()).isEqualTo("content");
    }

    @Test
    void evaluateCondition_WithPayloadFieldAccess_ShouldMatchMapPayload() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "order");
        payload.put("amount", 100);

        Map<String, Object> headers = new HashMap<>();
        org.springframework.messaging.Message<?> message = new GenericMessage<>(payload, headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "payload.type", ConditionOperator.EQUALS, "order");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, message);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateCondition_WithFullPayloadAccess_ShouldMatch() {
        // Given
        Map<String, Object> headers = new HashMap<>();
        org.springframework.messaging.Message<?> message = new GenericMessage<>("test payload", headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "payload", ConditionOperator.EQUALS, "test payload");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, message);

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void evaluateCondition_WithNullFieldValue_ShouldHandleNull() {
        // Given
        Map<String, Object> headers = new HashMap<>();
        org.springframework.messaging.Message<?> message = new GenericMessage<>("payload", headers);

        Rule rule = new Rule("rule", RuleType.INBOUND, sourceChannel);
        RuleCondition condition = new RuleCondition(rule, "header.nonexistent", ConditionOperator.EQUALS, "");
        rule.getConditions().add(condition);

        // When
        boolean result = ruleEngine.evaluateConditions(rule, message);

        // Then
        assertThat(result).isTrue();
    }
}
