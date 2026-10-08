package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import org.springframework.stereotype.Component;
import java.util.*;

/** Immutable configuration, encrypted by the job attribute converter. Pool assignment stays operational. */
@Component
public class JobConfiguration {
    private final ObjectMapper json;
    private final ChannelSettingsRepository channels;
    public JobConfiguration(ObjectMapper json, ChannelSettingsRepository channels) { this.json=json; this.channels=channels; }
    public record Channel(long id,long version,String name,ChannelType type,ChannelDirection direction,Map<String,String> properties) {
        static Channel capture(ChannelSettings channel) { return new Channel(channel.getId(),channel.getVersion(),channel.getName(),channel.getChannelType(),channel.getDirection(),Map.copyOf(channel.getProperties())); }
        public ChannelSettings restore() { return ChannelSettings.snapshot(id,name,type,direction,properties); }
    }
    public record Condition(long id,String field,ConditionOperator operator,String value,LogicalOperator connector) { }
    public record Action(long id,ActionType type,String script,Boolean filter,String key,String value,int priority,String target) { }
    public record Configuration(long id,long version,String name,RuleType type,OutboundPayloadMode payloadMode,int priority,
                                List<Condition> conditions,List<Action> actions,Channel source,Channel destination,MessageStatus initialStatus) {
        public Rule restore() {
            var rule=Rule.snapshot(id,name,type,source.restore()); rule.setPriority(priority); rule.setOutboundPayloadMode(payloadMode);
            if (initialStatus != null) rule.getLoadingProperties().put("initialStatus", initialStatus.name());
            for(var condition:conditions) {
                var item=RuleCondition.snapshot(condition.id(),rule,condition.field(),condition.operator(),condition.value());
                item.setLogicalOperator(condition.connector()); rule.getConditions().add(item);
            }
            for(var action:actions) {
                var item=RuleAction.snapshot(action.id(),rule,action.type()); item.setPriority(action.priority());
                item.setTransformationScript(action.script()); item.setFilterResult(action.filter());
                item.setMetadataKey(action.key()); item.setMetadataValue(action.value());
                if(action.type()==ActionType.ROUTE && destination!=null) item.setDestinationChannel(destination.restore());
                else item.setTargetChannel(action.target());
                rule.getActions().add(item);
            }
            return rule;
        }
    }
    public Configuration capture(Rule rule) {
        Channel target=null;
        var route=rule.getActions().stream().filter(a->a.getActionType()==ActionType.ROUTE).findFirst();
        if(route.isPresent()) {
            var action=route.get(); var channel=action.getDestinationChannel();
            if(channel==null && action.getTargetChannel()!=null) channel=channels.findByName(action.getTargetChannel()).orElse(null);
            if(channel!=null) target=Channel.capture(channel);
        }
        var conditions=rule.getConditions().stream().sorted(Comparator.comparing(RuleCondition::getId)).map(c->
                new Condition(c.getId(),c.getField(),c.getOperator(),c.getValue(),c.getLogicalOperator())).toList();
        var actions=rule.getActions().stream().sorted(Comparator.comparing(RuleAction::getPriority).thenComparing(RuleAction::getId)).map(a->
                new Action(a.getId(),a.getActionType(),a.getTransformationScript(),a.getFilterResult(),a.getMetadataKey(),a.getMetadataValue(),a.getPriority(),a.getTargetChannel())).toList();
        return new Configuration(rule.getId(),rule.getVersion(),rule.getName(),rule.getRuleType(),rule.getOutboundPayloadMode(),rule.getPriority(),conditions,actions,Channel.capture(rule.getSourceChannel()),target,
                rule.getRuleType() == RuleType.INBOUND ? rule.getInitialMessageStatus() : null);
    }
    public void freeze(RuleExecutionJob job) { freeze(job,capture(job.getRule())); }
    public void freeze(RuleExecutionJob job,Configuration configuration) {
        try {
            String serialized=json.writeValueAsString(configuration);job.setConfigurationSnapshot(serialized);
            job.setConfigurationHash(SourceDeliveryService.digest(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        catch(Exception error) { throw new IllegalStateException("Could not freeze job configuration",error); }
    }
    public Configuration read(RuleExecutionJob job) {
        // Existing queued jobs are adopted once; new jobs are frozen before the ingestion transaction commits.
        if(job.getConfigurationSnapshot()==null) freeze(job);
        try { return json.readValue(job.getConfigurationSnapshot(),Configuration.class); }
        catch(Exception error) { throw new IllegalStateException("Could not read frozen job configuration",error); }
    }
}
