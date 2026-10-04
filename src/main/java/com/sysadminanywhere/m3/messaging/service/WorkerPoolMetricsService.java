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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public WorkerPoolMetricsService(RuleWorkerPoolRepository pools,
                                    WorkerPoolMetricSampleRepository samples,
                                    DockerWorkerScaler docker, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.pools = pools;
        this.samples = samples;
        this.docker = docker;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${m3.worker.metrics-sample-delay-ms:30000}",
            initialDelayString = "${m3.worker.metrics-sample-delay-ms:30000}")
    @Transactional
    public void samplePools() {
        if (!docker.isConfigured()) return;
        for (RuleWorkerPool pool : pools.findAll()) {
            try {
                var containers = docker.containerLoads(pool.getName());
                for (var container : containers) jdbc.update("""
                    INSERT INTO worker_container_metric_sample(worker_pool_id,container_id,container_name,cpu_percent,memory_percent)
                    VALUES(?,?,?,?,?)
                    """, pool.getId(),container.id(),container.name(),container.cpuPercent(),container.memoryPercent());
                samples.save(new WorkerPoolMetricSample(pool,containers.stream().mapToDouble(DockerWorkerScaler.ContainerLoad::cpuPercent).sum(),
                        containers.stream().mapToDouble(DockerWorkerScaler.ContainerLoad::memoryPercent).average().orElse(0)));
            } catch (Exception e) {
                log.warn("Could not sample worker pool '{}' metrics", pool.getName(), e);
            }
        }
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void deleteOldSamples() {
        jdbc.update("DELETE FROM worker_container_metric_sample WHERE sampled_at < now()-interval '30 days'");
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

    @Transactional(readOnly = true)
    public java.util.List<ContainerSummary> containers(Long poolId) {
        return jdbc.query("""
            SELECT container_id,container_name,max(sampled_at),
            (array_agg(cpu_percent ORDER BY sampled_at DESC))[1],max(cpu_percent),avg(cpu_percent),
            (array_agg(memory_percent ORDER BY sampled_at DESC))[1],max(memory_percent),avg(memory_percent)
            FROM worker_container_metric_sample WHERE worker_pool_id=?
            GROUP BY container_id,container_name ORDER BY container_name,max(sampled_at) DESC
            """, (rs,row) -> new ContainerSummary(rs.getString(1),rs.getString(2),
                    new LoadSummary(rs.getDouble(4),rs.getDouble(5),rs.getDouble(6),
                            rs.getDouble(7),rs.getDouble(8),rs.getDouble(9),rs.getTimestamp(3).toInstant())),poolId);
    }
    public record ContainerSummary(String id,String name,LoadSummary load) { }

    private double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0d; }

    public record LoadSummary(double currentCpu, double maximumCpu, double averageCpu,
                              double currentMemory, double maximumMemory, double averageMemory,
                              Instant sampledAt) {}
}
