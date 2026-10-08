package com.sysadminanywhere.m3.messaging.integration.gateway;

import com.sysadminanywhere.m3.messaging.domain.RuleType;
import com.sysadminanywhere.m3.messaging.repository.RuleRepository;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import com.sysadminanywhere.m3.messaging.source.InboundSourceSpec;
import org.springframework.stereotype.Service;
import java.util.Map;

/** In-process adapters use an explicit loading rule and the native ingestion transaction. */
@Service
public class MessageGateway {
    private final RuleRepository rules;
    private final SourceDeliveryService deliveries;
    public MessageGateway(RuleRepository rules,SourceDeliveryService deliveries) {
        this.rules=rules; this.deliveries=deliveries;
    }
    public long receive(long ruleId,Object payload,String payloadType,Map<String,String> metadata,String deliveryKey) {
        var rule=rules.findById(ruleId).orElseThrow(() -> new IllegalArgumentException("Loading rule not found"));
        if (rule.getRuleType()!=RuleType.INBOUND || !Boolean.TRUE.equals(rule.getEnabled()) || rule.getWorkerPool()==null)
            throw new IllegalArgumentException("Use an active INBOUND rule with a worker pool");
        return deliveries.receive(InboundSourceSpec.fromRule(rule),payload,payloadType,metadata==null ? Map.of() : metadata,deliveryKey);
    }
}
