package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RuleService {

    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private void changed(Rule rule) { entityManager.lock(rule,jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT); }
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
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Rule createRule(String name, RuleType ruleType, Long sourceChannelId, Integer priority) {
        var sourceChannel = channelSettingsRepository.findForUpdate(sourceChannelId)
                .orElseThrow(() -> new IllegalArgumentException("Channel not found: " + sourceChannelId));
        var rule = new Rule(name, ruleType, sourceChannel);
        workerPoolRepository.findByName("default").ifPresent(rule::setWorkerPool);
        rule.setPriority(priority);
        return ruleRepository.save(rule);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Rule updateRule(Long ruleId, String name, @jakarta.annotation.Nullable String description,
                           RuleType ruleType, Long sourceChannelId, Integer priority, Boolean enabled) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var sourceChannel = channelSettingsRepository.findForUpdate(sourceChannelId)
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
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void deleteRule(Long ruleId) {
        if (executionJobRepository.existsByRule_Id(ruleId))
            throw new IllegalArgumentException("Rule has message history; disable it or remove its completed messages before deleting");
        ruleRepository.deleteById(ruleId);
    }

    @Transactional(readOnly = true)
    public Rule findById(Long id) {
        return ruleRepository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Rule> page(org.springframework.data.domain.Pageable pageable) {
        return ruleRepository.findAll(pageable);
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
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleCondition addCondition(Long ruleId, String field, ConditionOperator operator, String value, LogicalOperator logicalOperator) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        validateCondition(field, operator, value, logicalOperator);
        var condition = new RuleCondition(rule, field, operator, value);
        condition.setLogicalOperator(logicalOperator);
        changed(rule);
        return ruleConditionRepository.save(condition);
    }

    private static void validateCondition(String field, ConditionOperator operator, String value, LogicalOperator logicalOperator) {
        if (field == null || field.isBlank() || field.length() > RuleCondition.FIELD_MAX_LENGTH
                || !(field.equals("payload") || field.startsWith("header.") && field.length() > 7
                     || field.startsWith("payload.") && field.length() > 8))
            throw new IllegalArgumentException("Condition field must be payload, payload.field or header.name");
        if (value == null || value.length() > RuleCondition.VALUE_MAX_LENGTH || operator == null || logicalOperator == null)
            throw new IllegalArgumentException("Condition value, operator and connector are required");
        if (operator == ConditionOperator.REGEX) {
            try { com.google.re2j.Pattern.compile(value); }
            catch (com.google.re2j.PatternSyntaxException invalid) { throw new IllegalArgumentException("Invalid regular expression"); }
        }
        if (operator == ConditionOperator.GREATER || operator == ConditionOperator.LESS) {
            try { new java.math.BigDecimal(value); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Numeric conditions require a numeric value"); }
        }
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleCondition updateCondition(Long ruleId,Long conditionId,String field,ConditionOperator operator,String value,LogicalOperator connector,long version) {
        com.sysadminanywhere.m3.base.persistence.Revisions.check(ruleConditionRepository.findById(conditionId).orElseThrow().getVersion(),version);
        return updateCondition(ruleId,conditionId,field,operator,value,connector);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleCondition updateCondition(Long ruleId, Long conditionId, String field, ConditionOperator operator, String value, LogicalOperator logicalOperator) {
        validateCondition(field, operator, value, logicalOperator);
        var condition = ruleConditionRepository.findById(conditionId).orElseThrow();
        if (!condition.getRule().getId().equals(ruleId)) throw new IllegalArgumentException("Condition belongs to another rule");
        condition.setField(field); condition.setOperator(operator); condition.setValue(value); condition.setLogicalOperator(logicalOperator);
        changed(condition.getRule());
        return ruleConditionRepository.save(condition);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void deleteCondition(Long conditionId) {
        var condition=ruleConditionRepository.findById(conditionId).orElseThrow(); changed(condition.getRule());
        condition.getRule().getConditions().remove(condition);
        ruleConditionRepository.delete(condition);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleAction addAction(Long ruleId, ActionType actionType) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var action = new RuleAction(rule, actionType);
        changed(action.getRule());
        return ruleActionRepository.save(action);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void deleteAction(Long actionId) {
        var action=ruleActionRepository.findById(actionId).orElseThrow(); changed(action.getRule());
        action.getRule().getActions().remove(action);
        ruleActionRepository.delete(action);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void toggleEnabled(Long ruleId) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        channelSettingsRepository.findForUpdate(rule.getSourceChannel().getId()).orElseThrow();
        rule.setEnabled(!rule.getEnabled());
        com.sysadminanywhere.m3.messaging.source.LoadingPolicy.validate(rule, ruleRepository.findAll());
        ruleRepository.save(rule);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Rule saveConfiguration(Long ruleId,String name,String description,RuleType type,Long sourceId,Integer priority,Boolean enabled,
                                  Long poolId,Long destinationId,OutboundPayloadMode mode,java.util.Map<String,String> loading,Long expectedVersion) {
        if(ruleId!=null) com.sysadminanywhere.m3.base.persistence.Revisions.check(ruleRepository.findById(ruleId).orElseThrow().getVersion(),expectedVersion);
        return saveConfiguration(ruleId,name,description,type,sourceId,priority,enabled,poolId,destinationId,mode,loading);
    }

    /** Save the rule, pool assignment and destination together. */
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Rule saveConfiguration(Long ruleId, String name, String description, RuleType type,
                                  Long sourceId, Integer priority, Boolean enabled, Long poolId, Long destinationId) {
        return saveConfiguration(ruleId,name,description,type,sourceId,priority,enabled,poolId,destinationId,null);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Rule saveConfiguration(Long ruleId, String name, String description, RuleType type,
                                  Long sourceId, Integer priority, Boolean enabled, Long poolId, Long destinationId,
                                  java.util.Map<String,String> loading) {
        OutboundPayloadMode existingMode = ruleId == null ? OutboundPayloadMode.BODY
                : ruleRepository.findById(ruleId).map(Rule::getOutboundPayloadMode).orElse(OutboundPayloadMode.BODY);
        return saveConfiguration(ruleId, name, description, type, sourceId, priority, enabled, poolId,
                destinationId, existingMode, loading);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Rule saveConfiguration(Long ruleId, String name, String description, RuleType type,
                                  Long sourceId, Integer priority, Boolean enabled, Long poolId, Long destinationId,
                                  OutboundPayloadMode payloadMode, java.util.Map<String,String> loading) {
        if (name == null || name.isBlank() || type == null || priority == null || priority < 0 || priority > 9999)
            throw new IllegalArgumentException("Name, type and a priority between 0 and 9999 are required");
        if (type == RuleType.OUTBOUND && destinationId == null)
            throw new IllegalArgumentException("An outbound rule requires a destination channel");
        ChannelSettings destination = destinationId == null ? null : channelSettingsRepository.findById(destinationId)
                .orElseThrow(() -> new IllegalArgumentException("Destination channel was deleted"));
        if (destination != null && (destination.getDirection() != ChannelDirection.OUTBOUND
                || Boolean.TRUE.equals(enabled) && !Boolean.TRUE.equals(destination.getEnabled())))
            throw new IllegalArgumentException("Destination must be an enabled outbound channel");
        Rule previous = ruleId == null ? null : ruleRepository.findById(ruleId).orElseThrow();
        if (type == RuleType.OUTBOUND && sourceId == null)
            sourceId = previous == null ? destination.getId() : previous.getSourceChannel().getId();
        if (sourceId == null) throw new IllegalArgumentException("Source channel is required");
        if (type == RuleType.INBOUND && channelSettingsRepository.findById(sourceId).orElseThrow().getDirection() != ChannelDirection.INBOUND)
            throw new IllegalArgumentException("Loading rules require an inbound source channel");
        Rule saved = previous == null ? createRule(name, type, sourceId, priority)
                : updateRule(ruleId, name, description, type, sourceId, priority, enabled);
        saved.setOutboundPayloadMode(destination == null ? OutboundPayloadMode.BODY
                : payloadMode == null ? OutboundPayloadMode.BODY : payloadMode);
        saved.setEnabled(enabled);
        saved.setDescription(description);
        if (loading != null) {
            var keys = com.sysadminanywhere.m3.messaging.source.InboundSourceSpec.LOADING_KEYS;
            if (!keys.containsAll(loading.keySet())) throw new IllegalArgumentException("Unsupported loading property");
            for (String key : java.util.List.of("pollingInterval","minFileAgeMs"))
                if (loading.containsKey(key) && Integer.parseInt(loading.get(key)) < 1) throw new IllegalArgumentException(key + " must be positive");
            for (String key : java.util.List.of("recursive","deleteAfterProcessing","deleteRemoteFiles"))
                if (loading.containsKey(key) && !java.util.Set.of("true","false").contains(loading.get(key))) throw new IllegalArgumentException(key + " must be true or false");
            if (loading.containsKey("autoOffsetReset") && !java.util.Set.of("earliest","latest","none").contains(loading.get("autoOffsetReset")))
                throw new IllegalArgumentException("Invalid Kafka offset reset policy");
            saved.getLoadingProperties().clear(); saved.getLoadingProperties().putAll(loading);
        }
        if (poolId != null) assignWorkerPool(saved.getId(), poolId);
        var routes = saved.getActions().stream().filter(action -> action.getActionType() == ActionType.ROUTE)
                .sorted(java.util.Comparator.comparing(RuleAction::getPriority)
                        .thenComparing(RuleAction::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
        RuleAction route = routes.isEmpty() ? null : routes.getFirst();
        for (var item : routes) if (destination == null || item != route) saved.getActions().remove(item);
        if (destination != null) {
            if (route == null) { route = new RuleAction(saved, ActionType.ROUTE); saved.getActions().add(route); }
            route.setDestinationChannel(destination);
        }
        com.sysadminanywhere.m3.messaging.source.LoadingPolicy.validate(saved, ruleRepository.findAll());
        ruleRepository.flush();
        return saved;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleAction createAction(Long ruleId, ActionType type, String script, Boolean filter, String key, String value) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var action = new RuleAction(rule, type);
        configureAction(action, type, script, filter, key, value);
        changed(action.getRule());
        return ruleActionRepository.save(action);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleAction saveVisualAction(Long ruleId,Long actionId,ActionType type,String script,Boolean filter,String key,String value,Integer priority,Long version) {
        if(actionId!=null) com.sysadminanywhere.m3.base.persistence.Revisions.check(ruleActionRepository.findById(actionId).orElseThrow().getVersion(),version);
        return saveVisualAction(ruleId,actionId,type,script,filter,key,value,priority);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleAction saveVisualAction(Long ruleId, Long actionId, ActionType type, String script, Boolean filter, String key, String value, Integer priority) {
        if (priority == null || priority < 0 || priority > 9999) throw new IllegalArgumentException("Execution order must be between 0 and 9999");
        var action = actionId == null ? new RuleAction(ruleRepository.findById(ruleId).orElseThrow(), type)
                : ruleActionRepository.findById(actionId).orElseThrow();
        if (!action.getRule().getId().equals(ruleId)) throw new IllegalArgumentException("Action belongs to another rule");
        if (actionId != null && action.getActionType() == ActionType.ROUTE) throw new IllegalArgumentException("Select the destination in the rule form");
        configureAction(action, type, script, filter, key, value);
        action.setPriority(priority);
        changed(action.getRule());
        return ruleActionRepository.save(action);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void reorderActions(Long ruleId, List<Long> orderedIds) {
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var actions = rule.getActions().stream().filter(action -> action.getActionType() == ActionType.TRANSFORM
                || action.getActionType() == ActionType.ENRICH).toList();
        var expected = actions.stream().map(RuleAction::getId).collect(java.util.stream.Collectors.toSet());
        if (orderedIds == null || orderedIds.size() != expected.size()
                || !expected.equals(new java.util.HashSet<>(orderedIds)))
            throw new IllegalArgumentException("Actions changed; refresh the rule and try again");
        if (orderedIds.size() > 10000) throw new IllegalArgumentException("Too many actions to reorder");
        var byId = actions.stream().collect(java.util.stream.Collectors.toMap(RuleAction::getId, action -> action));
        for (int index = 0; index < orderedIds.size(); index++) byId.get(orderedIds.get(index)).setPriority(index);
        changed(rule);
        ruleActionRepository.saveAll(actions);
    }

    private static void configureAction(RuleAction action, ActionType type, String script, Boolean filter, String key, String value) {
        if (type == null || type == ActionType.ROUTE) throw new IllegalArgumentException("Select the destination in the rule form");
        action.setActionType(type);
        action.setTransformationScript(null); action.setFilterResult(null); action.setMetadataKey(null); action.setMetadataValue(null);
        if (type == ActionType.TRANSFORM) {
            if (script == null || script.isBlank()) throw new IllegalArgumentException("Transformation script is required");
            RuleEngine.validateTransformation(script);
            action.setTransformationScript(script);
        } else if (type == ActionType.FILTER) {
            if (filter == null) throw new IllegalArgumentException("Filter result is required");
            action.setFilterResult(filter);
        } else if (type == ActionType.ENRICH) {
            if (key == null || key.isBlank() || value == null || value.isBlank()) throw new IllegalArgumentException("Metadata key and value are required");
            action.setMetadataKey(key); action.setMetadataValue(value);
        }
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void assignWorkerPool(Long ruleId, Long workerPoolId) {
        if (workerPoolId == null) throw new IllegalArgumentException("A worker pool must be assigned");
        var rule = ruleRepository.findById(ruleId).orElseThrow();
        var pool = workerPoolRepository.findById(workerPoolId).orElseThrow();
        rule.setWorkerPool(pool);
        ruleRepository.save(rule);
        executionJobRepository.moveUnfinishedJobs(ruleId, workerPoolId);
    }
}
