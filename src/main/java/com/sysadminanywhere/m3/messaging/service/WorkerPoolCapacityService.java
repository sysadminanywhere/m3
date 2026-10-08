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
    @org.springframework.beans.factory.annotation.Autowired private WorkerCapacityAllocation allocation;
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
        reconcileAll();
    }

    public void removePool(String poolName) { scaler.removePool(poolName); }

    public void reconcileAll() {
        var targets=allocation.targets();
        for (var pool : pools.findAll()) {
            try { scaler.reconcile(pool,targets.getOrDefault(pool.getName(),0)); }
            catch (RuntimeException error) { log.warn("Could not reconcile pool '{}'",pool.getName(),error); }
        }
        scaler.removeOrphanPools(name -> pools.findByName(name).isPresent());
    }

    public String status(String poolName) {
        if (!scaler.isConfigured()) return "No Docker API";
        try {
            var pool = pools.findByName(poolName).orElseThrow();
            long backlog = queueDepth(pool);
            int target=effectiveReplicas(pool);
            String status=scaler.runningCount(poolName) + " / " + target + " · " + backlog + " jobs";
            return status;
        }
        catch (Exception e) { return "Unavailable"; }
    }

    public boolean isConfigured() { return scaler.isConfigured(); }
    public boolean limited(RuleWorkerPool pool) {
        long backlog=queueDepth(pool);
        int requested=allocation.requested(pool);
        return effectiveReplicas(pool)<requested;
    }
    public Long oldestQueuedSeconds(RuleWorkerPool pool) {
        return jdbc.queryForObject("SELECT extract(epoch FROM now()-min(j.created_at))::bigint FROM rule_execution_job j JOIN rule r ON r.rule_id=j.rule_id WHERE r.worker_pool_id=? AND j.status='PENDING'",Long.class,pool.getId());
    }
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public String scaleTarget(RuleWorkerPool pool) {
        return pool.isAutoScaleEnabled()
                ? "Auto " + pool.getMinReplicas() + "–" + pool.getMaxReplicas()
                : "Fixed " + pool.getDesiredReplicas();
    }

    private int effectiveReplicas(RuleWorkerPool pool) {
        return allocation.targets().getOrDefault(pool.getName(),0);
    }

    private long queueDepth(RuleWorkerPool pool) {
        return jobs.queueDepth(pool.getId());
    }
}
