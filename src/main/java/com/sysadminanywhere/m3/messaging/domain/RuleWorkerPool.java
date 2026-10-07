package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rule_worker_pool", uniqueConstraints = @UniqueConstraint(name = "uk_rule_worker_pool_name", columnNames = "name"))
public class RuleWorkerPool {

    @Version
    @Column(nullable = false, columnDefinition="bigint not null default 0")
    private long version;
    public long getVersion() { return version; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "worker_pool_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "desired_replicas", nullable = false)
    private int desiredReplicas = 1;

    @Column(name = "min_replicas", nullable = false)
    private int minReplicas = 1;

    @Column(name = "max_replicas", nullable = false)
    private int maxReplicas = 4;

    @Column(name = "auto_scale_enabled", nullable = false, columnDefinition = "boolean not null default false")
    private boolean autoScaleEnabled;

    @Column(name = "pending_jobs_per_worker", nullable = false, columnDefinition = "integer not null default 50")
    private int pendingJobsPerWorker = 50;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "workerPool", fetch = FetchType.LAZY)
    private List<Rule> rules = new ArrayList<>();

    protected RuleWorkerPool() {}

    public RuleWorkerPool(String name, int desiredReplicas, int minReplicas, int maxReplicas) {
        setName(name);
        setCapacity(desiredReplicas, minReplicas, maxReplicas, false, 50);
    }

    public RuleWorkerPool(String name, int desiredReplicas, int minReplicas, int maxReplicas,
                          boolean autoScaleEnabled, int pendingJobsPerWorker) {
        setName(name);
        setCapacity(desiredReplicas, minReplicas, maxReplicas, autoScaleEnabled, pendingJobsPerWorker);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getDesiredReplicas() { return desiredReplicas; }
    public int getMinReplicas() { return minReplicas; }
    public int getMaxReplicas() { return maxReplicas; }
    public boolean isAutoScaleEnabled() { return autoScaleEnabled; }
    public int getPendingJobsPerWorker() { return pendingJobsPerWorker; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Rule> getRules() { return rules; }

    public void setName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9-]{1,78}")) {
            throw new IllegalArgumentException("Pool name must be a lowercase slug (2-79 chars)");
        }
        this.name = name;
    }

    public void setCapacity(int desiredReplicas, int minReplicas, int maxReplicas,
                            boolean autoScaleEnabled, int pendingJobsPerWorker) {
        if (minReplicas < 1 || maxReplicas < minReplicas || desiredReplicas < minReplicas || desiredReplicas > maxReplicas) {
            throw new IllegalArgumentException("Replica counts must satisfy 1 <= min <= desired <= max");
        }
        if (pendingJobsPerWorker < 1) throw new IllegalArgumentException("Pending jobs per worker must be positive");
        this.desiredReplicas = desiredReplicas;
        this.minReplicas = minReplicas;
        this.maxReplicas = maxReplicas;
        this.autoScaleEnabled = autoScaleEnabled;
        this.pendingJobsPerWorker = pendingJobsPerWorker;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RuleWorkerPool pool)) return false;
        return id != null && id.equals(pool.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
