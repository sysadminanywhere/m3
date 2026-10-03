package com.sysadminanywhere.m3.messaging.integration.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableIntegration
@Profile("!worker")
public class IntegrationConfig {
}
