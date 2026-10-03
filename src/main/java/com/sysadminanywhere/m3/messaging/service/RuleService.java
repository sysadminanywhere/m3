package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RuleConditionRepository ruleConditionRepository;
    private final RuleActionRepository ruleActionRepository;
    private final ChannelSettingsRepository channelSettingsRepository;
    private final RuleWorkerPoolRepository workerPoolRepository;
    private final RuleExecutionJobRepository executionJobRepository;

    RuleService(RuleRepository ruleRepository,
                RuleConditionRepository ruleConditionRepository,
                RuleActionRepository ruleActionRepository,
                ChannelSettingsRepository channelSettingsRepository,
                RuleWorkerPoolRepository workerPoolRepository,
                RuleExecutionJobRepository executionJobRepository) {
        this.ruleRepository = ruleRepository;
        this.ruleConditionRepository = ruleConditionRepository;
        this.ruleActionRepository = ruleActionRepository;
        this.channelSettingsRepository = channelSettingsRepository;
        this.workerPoolRepository = workerPoolRepository;
        this.executionJobRepository = executionJobRepository;
    }

    @Transactional
    public Rule createRule(String name, RuleType ruleType, Long sourceChannelId, Integer priority) {
        var sourceChannel = channelSettingsRepository.findById(sourceChannelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + sourceChannelId));
        var rule = new Rule(name, ruleType, sourceChannel);
        workerPoolRepository.findByName("default").ifPresent(rule::setWorkerPool);
        rule.setPriority(priority);
        return ruleRepository.save(rule);
    }

    @Transactional
    public Rule updateRule(Long ruleId, String name, @jakarta.annotation.Nullable String description,
                           RuleType ruleType, Long sourceChannelId, Integer priority, Boolean enabled) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var sourceChannel = channelSettingsRepository.findById(sourceChannelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + sourceChannelId));
        rule.setName(name);
        rule.setDescription(description);
        rule.setRuleType(ruleType);
        rule.setSourceChannel(sourceChannel);
        rule.setPriority(priority);
        rule.setEnabled(enabled);
        return ruleRepository.save(rule);
    }

    @Transactional
    public void deleteRule(Long ruleId) {
        ruleRepository.deleteById(ruleId);
    }

    @Transactional(readOnly = true)
    public Rule findById(Long id) {
        return ruleRepository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Rule> findAll() {
        return ruleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Rule> findByRuleType(RuleType ruleType) {
        return ruleRepository.findByRuleType(ruleType);
    }

    @Transactional(readOnly = true)
    public List<Rule> findBySourceChannel(ChannelSettings sourceChannel) {
        return ruleRepository.findBySourceChannel(sourceChannel);
    }

    @Transactional(readOnly = true)
    public List<Rule> findEnabledBySourceChannel(ChannelSettings sourceChannel) {
        return ruleRepository.findEnabledBySourceChannelOrderByPriority(sourceChannel);
    }

    @Transactional(readOnly = true)
    public List<Rule> findBySourceChannelId(Long channelId) {
        return ruleRepository.findBySourceChannelId(channelId);
    }

    @Transactional(readOnly = true)
    public List<Rule> findEnabledBySourceChannelId(Long channelId) {
        return ruleRepository.findEnabledBySourceChannelIdOrderByPriority(channelId);
    }

    @Transactional(readOnly = true)
    public List<Rule> findEnabledBySourceChannelName(String channelName) {
        return channelSettingsRepository.findByName(channelName)
                .map(ruleRepository::findEnabledBySourceChannelOrderByPriority)
                .orElse(List.of());
    }

    @Transactional
    public RuleCondition addCondition(Long ruleId, String field, ConditionOperator operator, String value, LogicalOperator logicalOperator) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var condition = new RuleCondition(rule, field, operator, value);
        condition.setLogicalOperator(logicalOperator);
        return ruleConditionRepository.save(condition);
    }

    @Transactional
    public void deleteCondition(Long conditionId) {
        ruleConditionRepository.deleteById(conditionId);
    }

    @Transactional
    public RuleAction addAction(Long ruleId, ActionType actionType) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var action = new RuleAction(rule, actionType);
        return ruleActionRepository.save(action);
    }

    @Transactional
    public void deleteAction(Long actionId) {
        ruleActionRepository.deleteById(actionId);
    }

    @Transactional
    public void toggleEnabled(Long ruleId) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        rule.setEnabled(!rule.getEnabled());
        ruleRepository.save(rule);
    }

    @Transactional
    public void assignWorkerPool(Long ruleId, Long workerPoolId) {
        if (workerPoolId == null) throw new IllegalArgumentException("A worker pool must be assigned");
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var pool = workerPoolRepository.findById(workerPoolId).orElseThrow();
        rule.setWorkerPool(pool);
        ruleRepository.save(rule);
        executionJobRepository.moveUnfinishedJobs(ruleId, workerPoolId);
    }
}
