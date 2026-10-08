package com.sysadminanywhere.m3.messaging.broker;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitReceiptConfiguration {
    @Bean
    public CachingConnectionFactory receiptConnectionFactory(
            @Value("${m3.broker.rabbit.host:localhost}") String host,
            @Value("${m3.broker.rabbit.port:5672}") int port,
            @Value("${m3.broker.rabbit.username:m3}") String username,
            @Value("${m3.broker.rabbit.password:m3}") String password,
            @Value("${m3.broker.rabbit.virtual-host:/}") String virtualHost) {
        var factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        factory.setConnectionTimeout(5000);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean
    public RabbitAdmin receiptRabbitAdmin(CachingConnectionFactory receiptConnectionFactory) {
        return new RabbitAdmin(receiptConnectionFactory);
    }

    @Bean
    public RabbitTemplate receiptRabbitTemplate(CachingConnectionFactory receiptConnectionFactory) {
        var template = new RabbitTemplate(receiptConnectionFactory);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public Declarables receiptTopology(@Value("${m3.broker.rabbit.exchange:m3.events}") String exchangeName,
                                      @Value("${m3.broker.rabbit.queue:m3.message.received}") String queueName) {
        var exchange = new TopicExchange(exchangeName, true, false);
        var queue = new Queue(queueName, true);
        return new Declarables(exchange, queue, BindingBuilder.bind(queue).to(exchange).with("message.received"));
    }
    @Bean public Declarables processingTopology(@Value("${m3.broker.rabbit.exchange:m3.events}") String exchangeName,
            @Value("${m3.processing.events-queue:m3.processing.events}") String queueName,@Value("${m3.processing.events-ttl-ms:604800000}") long ttl) {
        if(ttl<1)throw new IllegalArgumentException("Processing event TTL must be positive");
        var exchange=new TopicExchange(exchangeName,true,false);var queue=new Queue(queueName,true,false,false,java.util.Map.of("x-message-ttl",ttl));
        return new Declarables(queue,BindingBuilder.bind(queue).to(exchange).with("processing.#"));
    }
}
