package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "worker_pool_metric_sample", indexes = {
        @Index(name = "idx_worker_pool_metric_sample_time", columnList = "worker_pool_id, sampled_at")
})
public class WorkerPoolMetricSample {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sample_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_pool_id", nullable = false)
    private RuleWorkerPool workerPool;

    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt = Instant.now();

    @Column(name = "cpu_percent", nullable = false)
    private double cpuPercent;

    @Column(name = "memory_percent", nullable = false)
    private double memoryPercent;

    protected WorkerPoolMetricSample() {}

    public WorkerPoolMetricSample(RuleWorkerPool workerPool, double cpuPercent, double memoryPercent) {
        this.workerPool = workerPool;
        this.cpuPercent = Math.max(0, cpuPercent);
        this.memoryPercent = Math.clamp(memoryPercent, 0, 100);
    }

    public Long getId() { return id; }
    public RuleWorkerPool getWorkerPool() { return workerPool; }
    public Instant getSampledAt() { return sampledAt; }
    public double getCpuPercent() { return cpuPercent; }
    public double getMemoryPercent() { return memoryPercent; }
}
