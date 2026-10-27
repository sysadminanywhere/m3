package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.RuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.GenericMessage;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RuleSimulationDialogTest {
    @Test void previewReportsIntermediateStatesInExecutionOrder() {
        var channel = new ChannelSettings("target", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND);
        var rule = new Rule("send", RuleType.OUTBOUND, channel);
        var transform = new RuleAction(rule, ActionType.TRANSFORM);
        transform.setTransformationScript("$.name"); transform.setPriority(1);
        var enrich = new RuleAction(rule, ActionType.ENRICH);
        enrich.setMetadataKey("category"); enrich.setMetadataValue("example"); enrich.setPriority(2);
        rule.getActions().add(enrich); rule.getActions().add(transform);
        var steps = new java.util.ArrayList<org.springframework.messaging.Message<?>>();
        new RuleEngine(null).applyTransformations(new GenericMessage<>(Map.of("name", "M3")),
                java.util.List.of(rule), (action, state) -> steps.add(state));
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getPayload()).isEqualTo("M3");
        assertThat(steps.get(0).getHeaders()).doesNotContainKey("category");
        assertThat(steps.get(1).getHeaders().get("category")).isEqualTo("example");
    }
    @Test void idDeliverySkipsBodyTransformationsButStillAddsMetadata() {
        var channel = new ChannelSettings("target", ChannelType.DIRECTORY, ChannelDirection.OUTBOUND);
        var rule = new Rule("send", RuleType.OUTBOUND, channel);
        var transform = new RuleAction(rule, ActionType.TRANSFORM);
        transform.setTransformationScript("$.name");
        var enrich = new RuleAction(rule, ActionType.ENRICH);
        enrich.setMetadataKey("category"); enrich.setMetadataValue("example");
        rule.getActions().add(transform); rule.getActions().add(enrich);
        var input = new GenericMessage<>(Map.of("name", "M3"));
        var engine = new RuleEngine(null);

        var idResult = RuleSimulationDialog.previewActions(engine, rule, input, true);
        assertThat(idResult.getPayload()).isEqualTo(Map.of("name", "M3"));
        assertThat(idResult.getHeaders().get("category")).isEqualTo("example");
        var bodyResult = RuleSimulationDialog.previewActions(engine, rule, input, false);
        assertThat(bodyResult.getPayload()).isEqualTo("M3");
        assertThat(bodyResult.getHeaders().get("category")).isEqualTo("example");
    }
}
