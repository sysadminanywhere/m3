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

    RuleService(RuleRepository ruleRepository,
                RuleConditionRepository ruleConditionRepository,
                RuleActionRepository ruleActionRepository) {
        this.ruleRepository = ruleRepository;
        this.ruleConditionRepository = ruleConditionRepository;
        this.ruleActionRepository = ruleActionRepository;
    }

    @Transactional
    public Rule createRule(String name, RuleType ruleType, String sourceChannel, Integer priority) {
        var rule = new Rule(name, ruleType, sourceChannel);
        rule.setPriority(priority);
        return ruleRepository.save(rule);
    }

    @Transactional
    public Rule updateRule(Long ruleId, String name, @jakarta.annotation.Nullable String description,
                           RuleType ruleType, String sourceChannel, Integer priority, Boolean enabled) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
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
    public List<Rule> findBySourceChannel(String sourceChannel) {
        return ruleRepository.findBySourceChannel(sourceChannel);
    }

    @Transactional(readOnly = true)
    public List<Rule> findEnabledBySourceChannel(String sourceChannel) {
        return ruleRepository.findEnabledBySourceChannelOrderByPriority(sourceChannel);
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
}
