package com.sysadminanywhere.m3.messaging.source;

/** A receiver whose asynchronous lifecycle can be reconciled by the worker. */
public interface InboundSourceRuntime extends AutoCloseable {
    boolean isRunning();
}
