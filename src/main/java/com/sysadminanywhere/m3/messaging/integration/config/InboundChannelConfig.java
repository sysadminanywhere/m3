package com.sysadminanywhere.m3.messaging.integration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.messaging.MessageChannel;

@Configuration
public class InboundChannelConfig {

    @Bean
    public MessageChannel messageInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel ruleRoutingChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel persistenceChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel defaultChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel jsonProcessingChannel() {
        return new DirectChannel();
    }
}
