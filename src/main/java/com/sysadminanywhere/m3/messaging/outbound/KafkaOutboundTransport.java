package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaOutboundTransport implements OutboundTransport {
    @Override public Set<ChannelType> types() { return Set.of(ChannelType.KAFKA); }
    @Override public void send(ChannelSettings channel, long id, PreparedOutboundDelivery delivery) throws Exception {
        var settings = com.sysadminanywhere.m3.messaging.source.InboundSourceSpec.from(channel);
        Map<String, Object> config = new HashMap<>();
        settings.properties().forEach((key, value) -> { if (key.startsWith("kafka.") && !value.isBlank()) config.put(key.substring(6), value); });
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.required("bootstrapServers"));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all"); config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000); config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        var producer = new KafkaProducer<byte[], byte[]>(config);
        try {
            String keyValue = delivery.metadata().getOrDefault("kafkaKey",Long.toString(id));
            byte[] key = "base64".equals(delivery.metadata().get("kafkaKeyEncoding"))
                    ? Base64.getDecoder().decode(keyValue) : keyValue.getBytes(StandardCharsets.UTF_8);
            var record = new ProducerRecord<>(settings.required("topic"), key,
                    "true".equals(delivery.metadata().get("kafkaTombstone")) && delivery.body().length == 0 ? null : delivery.body());
            delivery.metadata().forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
            record.headers().remove("contentType");
            record.headers().add("contentType", delivery.payloadType().getBytes(StandardCharsets.UTF_8));
            record.headers().remove("m3MessageId"); record.headers().add("m3MessageId", Long.toString(id).getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(15, TimeUnit.SECONDS);
        } finally { producer.close(Duration.ofSeconds(3)); }
    }
}
