package com.sysadminanywhere.m3.messaging.broker;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Component
public class ProcessingEventPublisher {
    private final RabbitTemplate template;private final String exchange;
    public ProcessingEventPublisher(RabbitTemplate receiptRabbitTemplate,@Value("${m3.broker.rabbit.exchange:m3.events}") String exchange){template=receiptRabbitTemplate;this.exchange=exchange;}
    public void publish(UUID id,String type,String body)throws Exception {
        var properties=new MessageProperties();properties.setContentType("application/json");properties.setContentEncoding("UTF-8");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(id.toString());properties.setType(type);
        var correlation=new CorrelationData(id.toString());template.send(exchange,type,new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),properties),correlation);
        if(!correlation.getFuture().get(5,TimeUnit.SECONDS).ack() || correlation.getReturned()!=null) throw new IllegalStateException("Processing event was not routed");
    }
}
