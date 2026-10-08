package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import com.sysadminanywhere.m3.messaging.repository.RuleWorkerPoolRepository;
import com.sysadminanywhere.m3.extensions.ExecutionPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** One installation-wide budget. No per-pool multiplication of the license limit. */
@Service
public class WorkerCapacityAllocation {
    private final RuleWorkerPoolRepository pools;
    private final JdbcTemplate jdbc;
    private final ExecutionPolicy policy;
    public WorkerCapacityAllocation(RuleWorkerPoolRepository pools,JdbcTemplate jdbc,ExecutionPolicy policy) {
        this.pools=pools; this.jdbc=jdbc; this.policy=policy;
    }
    public Map<String,Integer> targets() {
        var demands=pools.findAll().stream().map(this::snapshot).toList();
        long epoch=jdbc.queryForObject("SELECT floor(extract(epoch FROM now())/60)::bigint",Long.class);
        return policy.targets(demands,epoch);
    }
    private ExecutionPolicy.PoolDemand snapshot(RuleWorkerPool pool) {
        Long backlog=jdbc.queryForObject("SELECT count(*) FROM rule_execution_job j JOIN rule r ON r.rule_id=j.rule_id WHERE r.worker_pool_id=? AND j.status IN ('PENDING','PROCESSING')",Long.class,pool.getId());
        return new ExecutionPolicy.PoolDemand(pool.getId(),pool.getName(),pool.getCapacityPriority(),
                pool.getDesiredReplicas(),pool.getMinReplicas(),pool.getMaxReplicas(),
                pool.isAutoScaleEnabled(),pool.getPendingJobsPerWorker(),backlog==null?0:backlog);
    }
    public int requested(RuleWorkerPool pool) { return policy.requested(snapshot(pool)); }
    public void validate(String name, Long replacingId, int desired, int min, int max, boolean autoScale) {
        jdbc.query("SELECT pg_advisory_xact_lock(19732,1)",rs -> {});
        policy.validate(pools.findAll().stream().map(this::snapshot).toList(),replacingId,desired,min,max,autoScale);
    }
    public long used() { return jdbc.queryForObject("SELECT count(*) FROM worker_slot WHERE expires_at>now()",Long.class); }
}
