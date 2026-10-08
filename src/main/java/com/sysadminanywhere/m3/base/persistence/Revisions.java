package com.sysadminanywhere.m3.base.persistence;
public final class Revisions {
    private Revisions() { }
    public static void check(long actual, long expected) {
        if (actual != expected) throw new IllegalArgumentException("Configuration changed in another session. Reload before saving.");
    }
}
