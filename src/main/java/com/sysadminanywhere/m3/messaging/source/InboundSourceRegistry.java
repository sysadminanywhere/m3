package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.ChannelSettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

@Component
public class InboundSourceRegistry {
    private static final Logger log = LoggerFactory.getLogger(InboundSourceRegistry.class);
    private record Running(InboundSourceSpec source, AutoCloseable runtime) { }
    private final ChannelSettingsRepository channels;
    private final Map<ChannelType, InboundSourceFactory> factories = new EnumMap<>(ChannelType.class);
    private final Map<Long, Running> running = new HashMap<>();
    private final SourceHealth health;
    private volatile boolean ready;
    private final com.sysadminanywhere.m3.messaging.repository.RuleRepository rules;
    private final String pool;
    private final boolean worker;

    public InboundSourceRegistry(ChannelSettingsRepository channels, List<InboundSourceFactory> adapters, SourceHealth health,
            com.sysadminanywhere.m3.messaging.repository.RuleRepository rules,
            @org.springframework.beans.factory.annotation.Value("${m3.worker.pool:default}") String pool, org.springframework.core.env.Environment environment) {
        this.channels = channels;
        this.health = health;
        this.rules = rules; this.pool = pool; this.worker = environment.matchesProfiles("worker");
        for (var adapter : adapters) for (var type : adapter.types()) {
            if (factories.putIfAbsent(type, adapter) != null) throw new IllegalStateException("Duplicate source adapter for " + type);
        }
    }
    public void validate(ChannelSettings channel) {
        ChannelEndpointValidator.validate(channel);
        PayloadCodec.charset(channel.getProperties().get("charset"));
        PayloadCodec.charset(channel.getProperties().get("outputCharset"));
        if (channel.getDirection() == ChannelDirection.INBOUND && Boolean.TRUE.equals(channel.getEnabled())) {
            // Newly created channels do not have an ID until saved.
            var spec = new InboundSourceSpec(channel.getId() == null ? 0 : channel.getId(), channel.getName(),
                    channel.getChannelType(), Map.copyOf(channel.getProperties()));
            var factory = factories.get(spec.type());
            if (factory == null) throw new IllegalArgumentException("No source adapter for " + spec.type());
            // Loading policy belongs to a rule; endpoint validation is completed when its receiver starts.
        }
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready = worker; reconcile(); }

    @Scheduled(fixedDelayString = "${m3.sources.reconcile-delay-ms:3000}")
    public synchronized void reconcile() {
        if (!ready) return;
        var desired = new HashMap<Long, InboundSourceSpec>();
        var loadingRules = rules.findByRuleType(RuleType.INBOUND);
        for (var rule : loadingRules) {
            if (Boolean.TRUE.equals(rule.getEnabled()) && rule.getWorkerPool() != null && pool.equals(rule.getWorkerPool().getName())
                    && rule.getSourceChannel().getDirection() == ChannelDirection.INBOUND)
                try {
                    LoadingPolicy.validate(rule, loadingRules);
                    desired.put(rule.getId(), InboundSourceSpec.fromRule(rule));
                } catch (IllegalArgumentException error) { health.failure(rule.getId(), error); }
        }
        for (long id : new ArrayList<>(running.keySet())) {
            var receiver = running.get(id);
            if (!receiver.source().equals(desired.get(id))
                    || receiver.runtime() instanceof InboundSourceRuntime lifecycle && !lifecycle.isRunning()) stop(id);
        }
        for (var source : desired.values()) {
            long runtimeId = source.ruleId();
            if (running.containsKey(runtimeId)) continue;
            try {
                var factory = factories.get(source.type());
                if (factory == null) throw new IllegalArgumentException("No adapter for " + source.type());
                factory.validate(source);
                health.starting(source.runtimeId());
                running.put(runtimeId, new Running(source, factory.start(source)));
            } catch (Exception error) {
                health.failure(source.runtimeId(), error);
                log.warn("Source {} could not start; startup will be retried ({})", source.id(), error.getClass().getSimpleName());
            }
        }
    }
    public void restartChannel(ChannelSettings channel) { stopChannel(channel.getId()); }
    public void stopChannel(Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { stopForChannel(id); }
            });
        } else stopForChannel(id);
    }
    private synchronized void stopForChannel(long channelId) {
        for (var entry : new ArrayList<>(running.entrySet()))
            if (entry.getValue().source().id() == channelId) stop(entry.getKey());
    }
    private synchronized void stop(long id) {
        var runtime = running.remove(id);
        if (runtime != null) {
            try { runtime.runtime().close(); }
            catch (Exception error) { log.warn("Could not close source {} ({})", id, error.getClass().getSimpleName()); }
        }
        health.stopped(id);
    }
    @PreDestroy public synchronized void close() {
        ready = false;
        for (long id : new ArrayList<>(running.keySet())) stop(id);
    }
}
