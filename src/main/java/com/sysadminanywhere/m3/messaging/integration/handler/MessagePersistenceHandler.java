package com.sysadminanywhere.m3.messaging.integration.handler;

import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.service.InboundMessageEnqueueService;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.stereotype.Component;

/** All legacy inbound persistence paths use the same atomic ingestion pipeline. */
@Component
@Profile("!worker")
public class MessagePersistenceHandler {
    private final InboundMessageEnqueueService inbound;

    public MessagePersistenceHandler(InboundMessageEnqueueService inbound) { this.inbound = inbound; }

    @ServiceActivator(inputChannel = "persistenceChannel")
    public void persistMessage(org.springframework.messaging.Message<?> message) {
        Object direction = message.getHeaders().get("direction");
        if (direction != null && !MessageDirection.INBOUND.name().equals(direction.toString())) {
            throw new IllegalArgumentException("The inbound persistence channel only accepts INBOUND messages");
        }
        inbound.enqueue(message.getPayload(), message.getHeaders());
    }
}
