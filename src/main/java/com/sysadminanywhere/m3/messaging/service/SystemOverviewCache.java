package com.sysadminanywhere.m3.messaging.service;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
@Component @Profile("!worker")
public class SystemOverviewCache {
    private final SystemOverviewService service;
    private SystemOverviewService.Snapshot cached;
    private long refreshed;
    public SystemOverviewCache(SystemOverviewService service) { this.service=service; }
    public synchronized SystemOverviewService.Snapshot snapshot() {
        if(cached==null || System.nanoTime()-refreshed>java.time.Duration.ofSeconds(5).toNanos()) {
            cached=service.snapshot(); refreshed=System.nanoTime();
        }
        return cached;
    }
    public synchronized void invalidate() { refreshed=0; }
}
