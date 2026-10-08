package com.sysadminanywhere.m3.messaging.outbound;

/** Persist the uncertainty boundary immediately before irreversible transport I/O. */
final class DeliveryAttempt {
    private static final ThreadLocal<Runnable> START = new ThreadLocal<>();
    static void started() { var callback=START.get(); if(callback!=null) { callback.run(); START.remove(); } }
    static void run(Runnable start, CheckedSend send) throws Exception {
        START.set(start); try { send.run(); } finally { START.remove(); }
    }
    interface CheckedSend { void run() throws Exception; }
}
