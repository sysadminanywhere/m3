package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import com.sysadminanywhere.m3.messaging.domain.RuleJobStatus;
import com.sysadminanywhere.m3.messaging.repository.RuleWorkerPoolRepository;
import com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!worker")
public class WorkerPoolCapacityService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkerPoolCapacityService.class);
    private final RuleWorkerPoolRepository pools;
    private final RuleExecutionJobRepository jobs;
    private final DockerWorkerScaler scaler;

    public WorkerPoolCapacityService(RuleWorkerPoolRepository pools, RuleExecutionJobRepository jobs,
                                     DockerWorkerScaler scaler) {
        this.pools = pools;
        this.jobs = jobs;
        this.scaler = scaler;
    }

    public void reconcile(Long poolId) {
        RuleWorkerPool pool = pools.findById(poolId).orElseThrow();
        scaler.reconcile(pool, effectiveReplicas(pool));
    }

    public void removePool(String poolName) { scaler.removePool(poolName); }

    public void reconcileAll() {
        for (var pool : pools.findAll()) {
            try { scaler.reconcile(pool,effectiveReplicas(pool)); }
            catch (RuntimeException error) { log.warn("Could not reconcile pool '{}'",pool.getName(),error); }
        }
        scaler.removeOrphanPools(name -> pools.findByName(name).isPresent());
    }

    public String status(String poolName) {
        if (!scaler.isConfigured()) return "No Docker API";
        try {
            var pool = pools.findByName(poolName).orElseThrow();
            long backlog = queueDepth(pool);
            return scaler.runningCount(poolName) + " / " + effectiveReplicas(pool) + " · " + backlog + " jobs";
        }
        catch (Exception e) { return "Unavailable"; }
    }

    public boolean isConfigured() { return scaler.isConfigured(); }

    public String scaleTarget(RuleWorkerPool pool) {
        return pool.isAutoScaleEnabled()
                ? "Auto " + pool.getMinReplicas() + "–" + pool.getMaxReplicas()
                : "Fixed " + pool.getDesiredReplicas();
    }

    private int effectiveReplicas(RuleWorkerPool pool) {
        if (!pool.isAutoScaleEnabled()) return pool.getDesiredReplicas();
        long backlog = queueDepth(pool);
        long calculated = backlog == 0 ? pool.getMinReplicas()
                : (backlog + pool.getPendingJobsPerWorker() - 1) / pool.getPendingJobsPerWorker();
        return (int) Math.clamp(calculated, pool.getMinReplicas(), pool.getMaxReplicas());
    }

    private long queueDepth(RuleWorkerPool pool) {
        return jobs.queueDepth(pool.getId());
    }
}
