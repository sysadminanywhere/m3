package com.sysadminanywhere.m3.messaging.integration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;

@Configuration
@Profile("!worker")
public class OutboundChannelConfig {

    @Bean
    public MessageChannel emailOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel httpOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel jmsOutboundChannel() {
        return new DirectChannel();
    }
}
