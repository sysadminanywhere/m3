package com.sysadminanywhere.m3.messaging.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RabbitMessageReceivedPublisher implements MessageReceivedPublisher {
    private final RabbitTemplate template;
    private final ObjectMapper json;
    private final String exchange;

    public RabbitMessageReceivedPublisher(RabbitTemplate receiptRabbitTemplate, ObjectMapper json,
                                         @Value("${m3.broker.rabbit.exchange:m3.events}") String exchange) {
        this.template = receiptRabbitTemplate;
        this.json = json;
        this.exchange = exchange;
    }

    @Override
    public void publish(MessageReceivedEvent event) throws Exception {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.eventId().toString());
        properties.setType(event.eventType());
        var correlation = new CorrelationData(event.eventId().toString());
        template.send(exchange, "message.received", new Message(json.writeValueAsBytes(event), properties), correlation);
        var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirmation.ack() || correlation.getReturned() != null) {
            throw new IllegalStateException("Broker did not confirm routing of receipt event " + event.eventId());
        }
    }
}
