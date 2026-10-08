package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.ChannelType;
import com.sysadminanywhere.m3.messaging.service.SourceDeliveryService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import java.util.*;

@Component

public class KafkaSourceFactory implements InboundSourceFactory {
    private final SourceDeliveryService deliveries;
    private final SourceHealth health;
    public KafkaSourceFactory(SourceDeliveryService deliveries, SourceHealth health) { this.deliveries = deliveries; this.health = health; }
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.KAFKA); }
    @Override public void validate(InboundSourceSpec source) {
        source.required("bootstrapServers"); source.required("topic"); source.required("groupId");
        if (!Set.of("earliest", "latest", "none").contains(source.value("autoOffsetReset", "earliest")))
            throw new IllegalArgumentException("autoOffsetReset must be earliest, latest or none");
    }
    @Override public AutoCloseable start(InboundSourceSpec source) {
        Map<String, Object> config = new HashMap<>();
        source.properties().forEach((key, value) -> { if (key.startsWith("kafka.") && !value.isBlank()) config.put(key.substring(6), value); });
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, source.required("bootstrapServers"));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, source.required("groupId"));
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, source.value("autoOffsetReset", "earliest"));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        var consumerFactory = new DefaultKafkaConsumerFactory<Object, Object>(config);
        var properties = new ContainerProperties(source.required("topic"));
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        properties.setSyncCommits(true);
        properties.setConsumerRebalanceListener(new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
            @Override public void onPartitionsRevoked(java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) { health.starting(source.runtimeId()); }
            @Override public void onPartitionsAssigned(java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) { health.success(source.runtimeId()); }
        });
        properties.setMessageListener((AcknowledgingMessageListener<Object, Object>) (record, acknowledgement) -> {
            try {
                Map<String,String> metadata = new HashMap<>();
                metadata.put("kafkaTopic",record.topic()); metadata.put("kafkaPartition",Integer.toString(record.partition()));
                metadata.put("kafkaOffset",Long.toString(record.offset())); metadata.put("kafkaTimestamp",Long.toString(record.timestamp()));
                if (record.key() != null) {
                    metadata.put("kafkaKey",record.key() instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : record.key().toString());
                    if (record.key() instanceof byte[]) metadata.put("kafkaKeyEncoding","base64");
                }
                var headers = new ArrayList<Map<String,Object>>();
                for (var header : record.headers()) {
                    headers.add(ProtocolMetadata.header(header.key(),header.value()));
                    if (header.value() != null) ProtocolMetadata.scalar(metadata,"kafkaHeader."+header.key(),Base64.getEncoder().encodeToString(header.value()));
                }
                metadata.put("kafkaHeadersJson",ProtocolMetadata.json(headers));
                var charsetHeader = record.headers().lastHeader("charset");
                if (charsetHeader != null && charsetHeader.value() != null) {
                    try { metadata.put("charset",com.sysadminanywhere.m3.messaging.domain.PayloadCodec.decode(charsetHeader.value(),"UTF-8")); }
                    catch (IllegalArgumentException invalid) { metadata.put("charsetDeclarationError","Charset header is not UTF-8"); }
                }
                String payloadType = source.value("payloadType","application/octet-stream");
                var typeHeader = record.headers().lastHeader("contentType");
                if (typeHeader != null && typeHeader.value() != null) {
                    try { payloadType = com.sysadminanywhere.m3.messaging.domain.PayloadCodec.decode(typeHeader.value(),"UTF-8"); }
                    catch (IllegalArgumentException invalid) { metadata.put("contentTypeDeclarationError","Content type header is not UTF-8"); }
                }
                Object payload = record.value();
                if (payload == null) { payload=new byte[0]; metadata.put("kafkaTombstone","true"); }
                deliveries.receive(source,payload,payloadType,metadata,source.required("bootstrapServers")+":"+record.topic()+":"+record.partition()+":"+record.offset());
                acknowledgement.acknowledge(); health.success(source.runtimeId());
            } catch (Exception error) { health.failure(source.runtimeId(),error); throw error; }
        });
        var container = new KafkaMessageListenerContainer<Object, Object>(consumerFactory, properties);
        var errors = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(Map.of(), true);
        errors.setAckAfterHandle(false);
        container.setCommonErrorHandler(errors);
        container.setBeanName("source-kafka-" + source.runtimeId());
        container.start();
        return new InboundSourceRuntime() {
            @Override public boolean isRunning() { return container.isRunning(); }
            @Override public void close() { container.stop(); }
        };
    }
}
