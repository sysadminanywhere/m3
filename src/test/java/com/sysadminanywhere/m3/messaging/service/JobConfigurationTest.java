package com.sysadminanywhere.m3.messaging.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class JobConfigurationTest {
    @Test void preservesRulesAndEndpointAfterLiveConfigurationChanges() {
        var channels=mock(ChannelSettingsRepository.class);
        var configuration=new JobConfiguration(new ObjectMapper(),channels);
        var source=ChannelSettings.snapshot(1L,"source",ChannelType.DIRECTORY,ChannelDirection.INBOUND,Map.of("directoryPath","original"));
        var destination=ChannelSettings.snapshot(2L,"target",ChannelType.RABBITMQ,ChannelDirection.OUTBOUND,Map.of("host","original-host","password","private"));
        var rule=Rule.snapshot(3L,"rule",RuleType.INBOUND,source);
        rule.getLoadingProperties().put("initialStatus", "PROCESSING");
        var condition=RuleCondition.snapshot(4L,rule,"payload",ConditionOperator.EQUALS,"old"); rule.getConditions().add(condition);
        var route=RuleAction.snapshot(5L,rule,ActionType.ROUTE); route.setDestinationChannel(destination); rule.getActions().add(route);
        var job=new RuleExecutionJob(new Message(MessageDirection.INBOUND,"old","text/plain"),rule,new RuleWorkerPool("default",1,1,4));
        configuration.freeze(job);
        rule.getLoadingProperties().put("initialStatus", "PROCESSED");
        condition.setValue("new"); destination.getProperties().put("host","new-host"); source.getProperties().put("directoryPath","new-path");
        var frozen=configuration.read(job);
        assertThat(frozen.restore().getInitialMessageStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(frozen.restore().getConditions().iterator().next().getValue()).isEqualTo("old");
        assertThat(frozen.destination().properties()).containsEntry("host","original-host").containsEntry("password","private");
        assertThat(frozen.source().properties()).containsEntry("directoryPath","original");
    }
}
