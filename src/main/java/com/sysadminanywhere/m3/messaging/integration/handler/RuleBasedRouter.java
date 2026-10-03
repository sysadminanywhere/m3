package com.sysadminanywhere.m3.messaging.integration.handler;

import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.service.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.annotation.Router;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!worker")
public class RuleBasedRouter {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedRouter.class);

    @Autowired
    private RuleEngine ruleEngine;

    @Router(inputChannel = "ruleRoutingChannel")
    public String routeMessage(Message<?> message) {
        String channelName = (String) message.getHeaders().get("channelName");
        String fileName = (String) message.getHeaders().get("fileName");
        
        log.info("Router received message from channel='{}', fileName='{}', payload type={}", 
                channelName, fileName, message.getPayload().getClass().getSimpleName());
        log.debug("Message headers: {}", message.getHeaders());
        
        if (channelName == null) {
            log.warn("No channelName header found, routing to defaultChannel");
            return "defaultChannel";
        }

        List<Rule> applicableRules = ruleEngine.findApplicableRules(message, channelName);
        log.info("Found {} applicable rules for channel '{}'", applicableRules.size(), channelName);
        
        String targetChannel = ruleEngine.determineTargetChannel(message, applicableRules);

        if (targetChannel != null) {
            log.info("Routing message from {} to {} based on rules", channelName, targetChannel);
            return targetChannel;
        }

        log.info("No routing rule matched, routing to defaultChannel");
        return "defaultChannel";
    }
}
