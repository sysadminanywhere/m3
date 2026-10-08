package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.AsyncConsumerStartedEvent;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;

@Component

public class RabbitSourceFactory implements InboundSourceFactory {
    private final SourceDeliveryService deliveries;
    private final SourceHealth health;
    public RabbitSourceFactory(SourceDeliveryService deliveries, SourceHealth health) { this.deliveries = deliveries; this.health = health; }
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.RABBITMQ); }
    @Override public void validate(InboundSourceSpec source) {
        source.required("host"); source.required("queue"); source.required("username");
        if (source.number("port", 5672) > 65535) throw new IllegalArgumentException("Invalid AMQP port");
    }
    @Override public AutoCloseable start(InboundSourceSpec source) {
        var connection = new CachingConnectionFactory(source.required("host"), source.number("port", 5672));
        connection.setUsername(source.required("username")); connection.setPassword(source.value("password", ""));
        connection.setVirtualHost(source.value("virtualHost", "/")); connection.setConnectionTimeout(5000);
        var container = new SimpleMessageListenerContainer(connection);
        container.setApplicationEventPublisher(event -> {
            if (event instanceof AsyncConsumerStartedEvent) health.success(source.runtimeId());
            if (event instanceof ListenerContainerConsumerFailedEvent failed) health.failure(source.runtimeId(),
                    failed.getThrowable() instanceof Exception error ? error : new IllegalStateException("AMQP consumer failed"));
        });
        try {
            if (source.flag("declareQueue", false)) {
                var admin = new RabbitAdmin(connection);
                var queue = new org.springframework.amqp.core.Queue(source.required("queue"), true);
                admin.declareQueue(queue);
                if (!source.value("exchange", "").isBlank()) {
                    var exchange = new TopicExchange(source.value("exchange", ""), true, false);
                    admin.declareExchange(exchange);
                    admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(source.value("routingKey", "#")));
                }
            }
            container.setQueueNames(source.required("queue"));
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setPrefetchCount(1);
            container.setMissingQueuesFatal(false);
            container.setAutoDeclare(false);
            container.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
                try {
                var properties = message.getMessageProperties();
                Map<String, String> metadata = new HashMap<>();
                metadata.put("amqpQueue", source.required("queue"));
                if (properties.getReceivedExchange() != null) metadata.put("amqpExchange", properties.getReceivedExchange());
                if (properties.getReceivedRoutingKey() != null) metadata.put("amqpRoutingKey", properties.getReceivedRoutingKey());
                if (properties.getMessageId() != null) metadata.put("amqpMessageId", properties.getMessageId());
                if (properties.getCorrelationId() != null) metadata.put("amqpCorrelationId", properties.getCorrelationId());
                if (properties.getContentEncoding() != null) {
                    metadata.put("amqpContentEncoding", properties.getContentEncoding());
                    try {
                        if (java.nio.charset.Charset.isSupported(properties.getContentEncoding()))
                            metadata.put("charset", properties.getContentEncoding());
                    } catch (IllegalArgumentException ignored) { /* Non-charset encodings remain metadata, e.g. gzip. */ }
                }
                var headers = new ArrayList<Map<String,Object>>();
                properties.getHeaders().forEach((key,value) -> {
                    headers.add(ProtocolMetadata.header(key,value));
                    if (value != null) ProtocolMetadata.scalar(metadata,"amqpHeader."+key,
                            value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value.toString());
                });
                metadata.put("amqpHeadersJson",ProtocolMetadata.json(headers));
                var basic = new LinkedHashMap<String,Object>();
                basic.put("contentType",properties.getContentType());
                basic.put("contentEncoding",properties.getContentEncoding());
                basic.put("messageId",properties.getMessageId());
                basic.put("correlationId",properties.getCorrelationId());
                basic.put("replyTo",properties.getReplyTo());
                basic.put("expiration",properties.getExpiration());
                basic.put("type",properties.getType());
                basic.put("userId",properties.getUserId());
                basic.put("appId",properties.getAppId());
                basic.put("priority",properties.getPriority());
                basic.put("deliveryMode",properties.getReceivedDeliveryMode());
                basic.put("timestamp",properties.getTimestamp() == null ? null : properties.getTimestamp().toInstant().toString());
                basic.put("redelivered",properties.getRedelivered());
                metadata.put("amqpPropertiesJson",ProtocolMetadata.json(basic));
                if (channel == null) throw new IllegalStateException("AMQP consumer channel is missing");
                    String identity = properties.getMessageId();
                    String key = identity == null ? null : source.required("host") + ":" + source.value("port", "5672")
                            + ":" + source.value("virtualHost", "/") + ":" + source.required("queue") + ":" + identity;
                    deliveries.receive(source, message.getBody(), properties.getContentType() == null ? "application/octet-stream" : properties.getContentType(), metadata, key);
                    channel.basicAck(properties.getDeliveryTag(), false);
                    health.success(source.runtimeId());
                } catch (Exception error) {
                    health.failure(source.runtimeId(), error);
                    // Avoid a tight redelivery loop during a database outage.
                    try { Thread.sleep(1000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    if (channel != null) channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
                }
            });
            container.afterPropertiesSet(); container.start();
            return new InboundSourceRuntime() {
                @Override public boolean isRunning() { return container.isRunning(); }
                @Override public void close() { container.stop(); connection.destroy(); }
            };
        } catch (RuntimeException error) { container.destroy(); connection.destroy(); throw error; }
    }
}
