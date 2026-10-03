package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import com.sysadminanywhere.m3.messaging.domain.WorkerPoolMetricSample;
import com.sysadminanywhere.m3.messaging.repository.RuleWorkerPoolRepository;
import com.sysadminanywhere.m3.messaging.repository.WorkerPoolMetricSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Profile("!worker")
public class WorkerPoolMetricsService {
    private static final Logger log = LoggerFactory.getLogger(WorkerPoolMetricsService.class);
    private final RuleWorkerPoolRepository pools;
    private final WorkerPoolMetricSampleRepository samples;
    private final DockerWorkerScaler docker;

    public WorkerPoolMetricsService(RuleWorkerPoolRepository pools,
                                    WorkerPoolMetricSampleRepository samples,
                                    DockerWorkerScaler docker) {
        this.pools = pools;
        this.samples = samples;
        this.docker = docker;
    }

    @Scheduled(fixedDelayString = "${m3.worker.metrics-sample-delay-ms:30000}",
            initialDelayString = "${m3.worker.metrics-sample-delay-ms:30000}")
    @Transactional
    public void samplePools() {
        if (!docker.isConfigured()) return;
        for (RuleWorkerPool pool : pools.findAll()) {
            try {
                var load = docker.load(pool.getName());
                samples.save(new WorkerPoolMetricSample(pool, load.cpuPercent(), load.memoryPercent()));
            } catch (Exception e) {
                log.warn("Could not sample worker pool '{}' metrics", pool.getName(), e);
            }
        }
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void deleteOldSamples() {
        samples.deleteOlderThan(Instant.now().minus(java.time.Duration.ofDays(30)));
    }

    @Transactional(readOnly = true)
    public LoadSummary summary(Long poolId) {
        var latest = samples.findTopByWorkerPool_IdOrderBySampledAtDesc(poolId);
        return new LoadSummary(
                latest.map(WorkerPoolMetricSample::getCpuPercent).orElse(0d),
                number(samples.maximumCpu(poolId)), number(samples.averageCpu(poolId)),
                latest.map(WorkerPoolMetricSample::getMemoryPercent).orElse(0d),
                number(samples.maximumMemory(poolId)), number(samples.averageMemory(poolId)),
                latest.map(WorkerPoolMetricSample::getSampledAt).orElse(null));
    }

    private double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0d; }

    public record LoadSummary(double currentCpu, double maximumCpu, double averageCpu,
                              double currentMemory, double maximumMemory, double averageMemory,
                              Instant sampledAt) {}
}
