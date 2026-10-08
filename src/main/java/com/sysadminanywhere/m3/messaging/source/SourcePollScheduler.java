package com.sysadminanywhere.m3.messaging.source;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component

public class SourcePollScheduler {
    private static final Logger log = LoggerFactory.getLogger(SourcePollScheduler.class);
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(8,
            Thread.ofPlatform().daemon().name("source-poll-", 0).factory());
    private final SourceHealth health;
    private final javax.sql.DataSource datasource;
    public SourcePollScheduler(SourceHealth health, javax.sql.DataSource datasource) { this.health = health; this.datasource = datasource; }
    public interface Poll { void run() throws Exception; }
    public AutoCloseable schedule(InboundSourceSpec source, Poll poll) {
        var closed = new AtomicBoolean();
        var future = executor.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            try {
                // Session lock coordinates replicas while each ingestion commits independently before file deletion.
                if (source.ruleId() == null) poll.run();
                else try (var connection = datasource.getConnection()) {
                    try (var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(19731,hashtext(?))")) {
                        lock.setString(1, "rule:" + source.ruleId());
                        try (var row = lock.executeQuery()) {
                            row.next(); if (!row.getBoolean(1)) return;
                        }
                    }
                    try { poll.run(); }
                    finally { try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(19731,hashtext(?))")) {
                        unlock.setString(1, "rule:" + source.ruleId()); unlock.execute();
                    } catch (Exception unlockFailure) {
                        // Session advisory locks must not leak back into the connection pool.
                        try { connection.abort(Runnable::run); }
                        catch (Exception closeFailure) { unlockFailure.addSuppressed(closeFailure); }
                        throw unlockFailure;
                    } }
                }
                if (!closed.get()) health.success(source.runtimeId());
            }
            catch (Exception error) {
                if (!closed.get()) {
                    health.failure(source.runtimeId(), error);
                    log.warn("Source {} failed; it will be polled again ({})", source.id(), error.getClass().getSimpleName());
                }
            }
        }, 0, source.number("pollingInterval", 5000), TimeUnit.MILLISECONDS);
        return new InboundSourceRuntime() {
            @Override public boolean isRunning() { return !closed.get() && !future.isDone(); }
            @Override public void close() { closed.set(true); future.cancel(true); }
        };
    }
    @PreDestroy public void close() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
