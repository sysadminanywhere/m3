package com.sysadminanywhere.m3.messaging.integration.handler;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MessagePersistenceHandler {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceHandler.class);

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @ServiceActivator(inputChannel = "persistenceChannel")
    @Transactional
    public void persistMessage(org.springframework.messaging.Message<?> springMessage) {
        log.info("PersistenceHandler received message, headers: {}", springMessage.getHeaders());
        try {
            String sourceSystem = (String) springMessage.getHeaders().get("sourceSystem");
            if (sourceSystem == null) {
                sourceSystem = (String) springMessage.getHeaders().get("channelName");
            }
            String targetSystem = (String) springMessage.getHeaders().get("targetSystem");
            String payloadType = (String) springMessage.getHeaders().get("payloadType");

            log.info("Extracted from headers: sourceSystem={}, targetSystem={}, payloadType={}",
                    sourceSystem, targetSystem, payloadType);
            
            if (payloadType == null) {
                payloadType = "json";
            }

            String payloadJson;
            Object payload = springMessage.getPayload();
            
            log.info("Payload type: {}, content preview: {}", 
                    payload.getClass().getSimpleName(),
                    payload.toString().substring(0, Math.min(100, payload.toString().length())));
            
            if (payload instanceof String str) {
                payloadJson = str;
            } else if (payload instanceof byte[] bytes) {
                payloadJson = new String(bytes);
            } else {
                payloadJson = objectMapper.writeValueAsString(payload);
            }
            
            MessageDirection direction = MessageDirection.INBOUND;
            String directionHeader = (String) springMessage.getHeaders().get("direction");
            if (directionHeader != null) {
                direction = MessageDirection.valueOf(directionHeader);
            }

            log.info("Creating message: direction={}, payloadLength={}, sourceSystem={}",
                    direction, payloadJson.length(), sourceSystem);

            var message = new Message(direction, payloadJson, payloadType);
            message.setSourceSystem(sourceSystem);
            message.setTargetSystem(targetSystem);

            // Add metadata from headers
            addMetadataFromHeader(message, springMessage, "fileName");
            addMetadataFromHeader(message, springMessage, "fileSize");
            addMetadataFromHeader(message, springMessage, "encoding");
            addMetadataFromHeader(message, springMessage, "filePath");
            addMetadataFromHeader(message, springMessage, "channelName");

            message = messageRepository.save(message);

            log.info("Message persisted successfully with ID: {}, from channel: {}", message.getId(), sourceSystem);
        } catch (Exception e) {
            log.error("Failed to persist message", e);
        }
    }

    private void addMetadataFromHeader(Message message, org.springframework.messaging.Message<?> springMessage, String headerName) {
        Object value = springMessage.getHeaders().get(headerName);
        if (value != null) {
            message.addMetadata(headerName, value.toString());
        }
    }
}
