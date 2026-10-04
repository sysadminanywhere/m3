package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

    @Mock
    private RuleRepository ruleRepository;

    @Mock
    private RuleConditionRepository ruleConditionRepository;

    @Mock
    private RuleActionRepository ruleActionRepository;

    @Mock
    private ChannelSettingsRepository channelSettingsRepository;

    @Mock
    private RuleWorkerPoolRepository workerPoolRepository;

    @Mock
    private RuleExecutionJobRepository executionJobRepository;

    @InjectMocks
    private RuleService ruleService;

    private ChannelSettings sourceChannel;
    private Rule testRule;

    @BeforeEach
    void setUp() {
        sourceChannel = new ChannelSettings("source-channel", ChannelType.DIRECTORY, ChannelDirection.INBOUND);
        testRule = new Rule("test-rule", RuleType.INBOUND, sourceChannel);
    }

    @Test
    void createRule_WhenSourceChannelExists_ShouldCreateRule() {
        // Given
        when(channelSettingsRepository.findForUpdate(1L)).thenReturn(Optional.of(sourceChannel));
        RuleWorkerPool defaultPool = new RuleWorkerPool("default", 1, 1, 4);
        when(workerPoolRepository.findByName("default")).thenReturn(Optional.of(defaultPool));
        when(ruleRepository.save(any(Rule.class))).thenAnswer(inv -> {
            Rule r = inv.getArgument(0);
            return r;
        });

        // When
        Rule result = ruleService.createRule("new-rule", RuleType.INBOUND, 1L, 10);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("new-rule");
        assertThat(result.getRuleType()).isEqualTo(RuleType.INBOUND);
        assertThat(result.getSourceChannel()).isEqualTo(sourceChannel);
        assertThat(result.getPriority()).isEqualTo(10);
        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getWorkerPool()).isSameAs(defaultPool);
        verify(ruleRepository).save(result);
    }

    @Test
    void createRule_WhenSourceChannelNotExists_ShouldThrowException() {
        // Given
        when(channelSettingsRepository.findForUpdate(1L)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> ruleService.createRule("rule", RuleType.INBOUND, 1L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel not found");
    }

    @Test
    void updateRule_WhenRuleAndChannelExist_ShouldUpdateRule() {
        // Given
        ChannelSettings newChannel = new ChannelSettings("new-channel", ChannelType.FTP, ChannelDirection.INBOUND);
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(testRule));
        when(channelSettingsRepository.findForUpdate(2L)).thenReturn(Optional.of(newChannel));
        when(ruleRepository.save(any(Rule.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Rule result = ruleService.updateRule(1L, "updated-name", "description", RuleType.OUTBOUND, 2L, 5, false);

        // Then
        assertThat(result.getName()).isEqualTo("updated-name");
        assertThat(result.getDescription()).isEqualTo("description");
        assertThat(result.getRuleType()).isEqualTo(RuleType.OUTBOUND);
        assertThat(result.getSourceChannel()).isEqualTo(newChannel);
        assertThat(result.getPriority()).isEqualTo(5);
        assertThat(result.getEnabled()).isFalse();
    }

    @Test
    void deleteRule_ShouldDeleteFromRepository() {
        // When
        ruleService.deleteRule(1L);

        // Then
        verify(ruleRepository).deleteById(1L);
    }

    @Test
    void findById_WhenRuleExists_ShouldReturnRule() {
        // Given
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(testRule));

        // When
        Rule result = ruleService.findById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("test-rule");
    }

    @Test
    void findById_WhenRuleNotExists_ShouldReturnNull() {
        // Given
        when(ruleRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Rule result = ruleService.findById(1L);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void findAll_ShouldReturnAllRules() {
        // Given
        when(ruleRepository.findAll()).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findAll();

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findByRuleType_ShouldReturnFilteredRules() {
        // Given
        when(ruleRepository.findByRuleType(RuleType.INBOUND)).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findByRuleType(RuleType.INBOUND);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findBySourceChannel_ShouldReturnRulesForChannel() {
        // Given
        when(ruleRepository.findBySourceChannel(sourceChannel)).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findBySourceChannel(sourceChannel);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findEnabledBySourceChannel_ShouldReturnEnabledRules() {
        // Given
        when(ruleRepository.findEnabledBySourceChannelOrderByPriority(sourceChannel)).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findEnabledBySourceChannel(sourceChannel);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findBySourceChannelId_ShouldReturnRules() {
        // Given
        when(ruleRepository.findBySourceChannelId(1L)).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findBySourceChannelId(1L);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findEnabledBySourceChannelId_ShouldReturnEnabledRulesOrdered() {
        // Given
        when(ruleRepository.findEnabledBySourceChannelIdOrderByPriority(1L)).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findEnabledBySourceChannelId(1L);

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findEnabledBySourceChannelName_WhenChannelExists_ShouldReturnRules() {
        // Given
        when(channelSettingsRepository.findByName("source-channel")).thenReturn(Optional.of(sourceChannel));
        when(ruleRepository.findEnabledBySourceChannelOrderByPriority(sourceChannel)).thenReturn(List.of(testRule));

        // When
        List<Rule> result = ruleService.findEnabledBySourceChannelName("source-channel");

        // Then
        assertThat(result).hasSize(1);
    }

    @Test
    void findEnabledBySourceChannelName_WhenChannelNotExists_ShouldReturnEmptyList() {
        // Given
        when(channelSettingsRepository.findByName("unknown")).thenReturn(Optional.empty());

        // When
        List<Rule> result = ruleService.findEnabledBySourceChannelName("unknown");

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void addCondition_ShouldCreateAndSaveCondition() {
        // Given
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(testRule));
        when(ruleConditionRepository.save(any(RuleCondition.class))).thenAnswer(inv -> {
            RuleCondition c = inv.getArgument(0);
            return c;
        });

        // When
        RuleCondition result = ruleService.addCondition(
                1L,
                "header.contentType",
                ConditionOperator.EQUALS,
                "application/json",
                LogicalOperator.AND
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRule()).isEqualTo(testRule);
        assertThat(result.getField()).isEqualTo("header.contentType");
        assertThat(result.getOperator()).isEqualTo(ConditionOperator.EQUALS);
        assertThat(result.getValue()).isEqualTo("application/json");
        assertThat(result.getLogicalOperator()).isEqualTo(LogicalOperator.AND);
    }

    @Test
    void deleteCondition_ShouldDeleteFromRepository() {
        // When
        ruleService.deleteCondition(1L);

        // Then
        verify(ruleConditionRepository).deleteById(1L);
    }

    @Test
    void addAction_ShouldCreateAndSaveAction() {
        // Given
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(testRule));
        when(ruleActionRepository.save(any(RuleAction.class))).thenAnswer(inv -> {
            RuleAction a = inv.getArgument(0);
            return a;
        });

        // When
        RuleAction result = ruleService.addAction(1L, ActionType.ROUTE);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getRule()).isEqualTo(testRule);
        assertThat(result.getActionType()).isEqualTo(ActionType.ROUTE);
    }

    @Test
    void deleteAction_ShouldDeleteFromRepository() {
        // When
        ruleService.deleteAction(1L);

        // Then
        verify(ruleActionRepository).deleteById(1L);
    }

    @Test
    void toggleEnabled_ShouldToggleRuleState() {
        // Given
        Rule rule = spy(new Rule("rule", RuleType.INBOUND, sourceChannel));
        when(channelSettingsRepository.findForUpdate(sourceChannel.getId())).thenReturn(Optional.of(sourceChannel));
        when(ruleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        ruleService.toggleEnabled(1L);

        // Then
        verify(rule).setEnabled(false);
        verify(ruleRepository).save(rule);
    }
}
