package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class RabbitOutboundTransport implements OutboundTransport {
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.RABBITMQ); }
    @Override public void send(ChannelSettings channel, long id, PreparedOutboundDelivery delivery) throws Exception {
        var settings = com.sysadminanywhere.m3.messaging.source.InboundSourceSpec.from(channel);
        var connection = new CachingConnectionFactory(settings.required("host"), settings.number("port", 5672));
        connection.setUsername(settings.required("username")); connection.setPassword(settings.value("password", ""));
        connection.setVirtualHost(settings.value("virtualHost", "/")); connection.setConnectionTimeout(5000);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); connection.setPublisherReturns(true);
        try {
            String exchange = settings.value("exchange", "");
            String key = exchange.isBlank() ? settings.required("queue") : settings.required("routingKey");
            if (settings.flag("declareQueue", false)) {
                var admin = new RabbitAdmin(connection); var queue = new Queue(settings.required("queue"), true);
                admin.declareQueue(queue);
                if (!exchange.isBlank()) {
                    var topic = new TopicExchange(exchange, true, false); admin.declareExchange(topic);
                    admin.declareBinding(BindingBuilder.bind(queue).to(topic).with(key));
                }
            }
            var properties = new MessageProperties(); properties.setContentType(delivery.payloadType());
            if (delivery.metadata().containsKey("charset")) properties.setContentEncoding(delivery.metadata().get("charset"));
            properties.setMessageId("m3:" + id); properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            delivery.metadata().forEach(properties::setHeader); properties.setHeader("m3MessageId", Long.toString(id));
            var template = new RabbitTemplate(connection); template.setMandatory(true);
            var correlation = new CorrelationData("m3:" + id);
            template.send(exchange, key, new org.springframework.amqp.core.Message(delivery.body(), properties), correlation);
            if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack() || correlation.getReturned() != null)
                throw new IOException("Broker did not confirm routing of outbound message");
        } finally { connection.destroy(); }
    }
}
