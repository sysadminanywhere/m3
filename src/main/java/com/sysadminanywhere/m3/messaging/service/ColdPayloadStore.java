package com.sysadminanywhere.m3.messaging.service;

public interface ColdPayloadStore {
    default void delete(String key) { throw new UnsupportedOperationException("Cold object deletion is not supported"); }
    boolean configured();
    void put(String key, byte[] content);
    byte[] get(String key);
}
