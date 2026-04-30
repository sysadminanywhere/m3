package com.sysadminanywhere.m3.messaging.integration.gateway;

import org.springframework.integration.annotation.Gateway;
import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.messaging.Message;

@MessagingGateway
public interface MessageGateway {

    @Gateway(requestChannel = "messageInputChannel")
    void sendMessage(Message<?> message);

    @Gateway(requestChannel = "messageInputChannel")
    void sendPayload(Object payload);
}
