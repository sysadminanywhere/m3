package com.sysadminanywhere.m3.messaging.integration.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.dsl.IntegrationFlow;
import com.sysadminanywhere.m3.messaging.source.FilePayloads;
import com.sysadminanywhere.m3.messaging.service.InboundMessageEnqueueService;

import java.io.File;
import java.nio.file.Files;

@Configuration
@Profile("!worker")
public class MessageProcessingFlow {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessingFlow.class);

    @Bean
    public IntegrationFlow messageInputToRouterFlow(InboundMessageEnqueueService enqueueService) {
        return IntegrationFlow.from("messageInputChannel")
                .log("messageInputChannel.received", m -> "Received from channel: " + m.getHeaders().get("channelName"))
                .handle((payload, headers) -> {
                    String channelName = (String) headers.get("channelName");
                    log.info("Processing payload from channel '{}', type: {}", 
                            channelName, payload.getClass().getSimpleName());
                    
                    if (payload instanceof File file) {
                        try {
                            log.info("Reading file: {} ({} bytes)", file.getAbsolutePath(), file.length());
                            byte[] fileBytes;
                            try (var input = Files.newInputStream(file.toPath())) { fileBytes = FilePayloads.read(input); }
                            String base64Content = java.util.Base64.getEncoder().encodeToString(fileBytes);
                            log.info("File read and encoded to Base64, length: {} chars", base64Content.length());
                            return org.springframework.messaging.support.MessageBuilder
                                    .withPayload(base64Content)
                                    .copyHeaders(headers)
                                    .setHeader("sourceSystem", channelName)
                                    .setHeader("payloadType", "file")
                                    .setHeader("fileName", file.getName())
                                    .setHeader("fileSize", fileBytes.length)
                                    .setHeader("encoding", "base64")
                                    .build();
                        } catch (Exception e) {
                            log.error("Failed to read file: {}", file.getAbsolutePath(), e);
                            throw new RuntimeException("Failed to read file: " + file.getAbsolutePath(), e);
                        }
                    }
                    
                    log.info("Payload passed through without transformation");
                    return payload;
                })
                .handle((payload, headers) -> {
                    enqueueService.enqueue(payload, new org.springframework.messaging.MessageHeaders(headers));
                    return null;
                })
                .get();
    }

    @Bean
    public IntegrationFlow defaultChannelPersistenceFlow() {
        return IntegrationFlow.from("defaultChannel")
                .handle((payload, headers) -> {
                    log.info("Message routed to defaultChannel (no rules matched), headers: {}", headers);
                    return payload;
                })
                .channel("persistenceChannel")
                .get();
    }
}
