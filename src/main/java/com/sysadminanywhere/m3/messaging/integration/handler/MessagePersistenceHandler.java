package com.sysadminanywhere.m3.messaging.integration.handler;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Component;

@Component
public class MessagePersistenceHandler {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceHandler.class);

    @Autowired
    private MessageService messageService;

    @Autowired
    private ObjectMapper objectMapper;

    @ServiceActivator(inputChannel = "persistenceChannel")
    public void persistMessage(org.springframework.messaging.Message<?> springMessage) {
        try {
            String sourceSystem = (String) springMessage.getHeaders().get("sourceSystem");
            String targetSystem = (String) springMessage.getHeaders().get("targetSystem");
            String payloadType = (String) springMessage.getHeaders().get("payloadType");
            
            if (payloadType == null) {
                payloadType = "json";
            }

            String payloadJson = objectMapper.writeValueAsString(springMessage.getPayload());
            
            MessageDirection direction = MessageDirection.INBOUND;
            String directionHeader = (String) springMessage.getHeaders().get("direction");
            if (directionHeader != null) {
                direction = MessageDirection.valueOf(directionHeader);
            }

            Message message = messageService.createMessage(direction, payloadJson, payloadType, sourceSystem, targetSystem);
            
            log.info("Message persisted with ID: {}", message.getId());
        } catch (Exception e) {
            log.error("Failed to persist message", e);
        }
    }
}
