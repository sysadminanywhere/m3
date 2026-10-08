package com.sysadminanywhere.m3.messaging.broker;

/** Implement this contract to deliver receipt events through another broker. */
public interface MessageReceivedPublisher {
    void publish(MessageReceivedEvent event) throws Exception;
}
