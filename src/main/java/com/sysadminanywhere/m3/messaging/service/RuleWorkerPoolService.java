package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import com.sysadminanywhere.m3.messaging.repository.RuleWorkerPoolRepository;
import com.sysadminanywhere.m3.messaging.repository.RuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RuleWorkerPoolService {
    private final RuleWorkerPoolRepository repository;
    private final RuleRepository ruleRepository;
    private final com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository jobs;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RuleWorkerPoolService(RuleWorkerPoolRepository repository, RuleRepository ruleRepository,
            com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository jobs, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public long ruleCount(Long poolId) { return ruleRepository.countByWorkerPool_Id(poolId); }

    @Transactional(readOnly = true)
    public List<RuleWorkerPool> findAll() {
        return repository.findAll().stream().sorted(java.util.Comparator.comparing(RuleWorkerPool::getName)).toList();
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleWorkerPool create(String name, int replicas, int minReplicas, int maxReplicas,
                                 boolean autoScaleEnabled, int pendingJobsPerWorker) {
        if (repository.existsByName(name)) throw new IllegalArgumentException("Worker pool already exists: " + name);
        return repository.save(new RuleWorkerPool(name, replicas, minReplicas, maxReplicas,
                autoScaleEnabled, pendingJobsPerWorker));
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleWorkerPool updateCapacity(Long id,int replicas,int min,int max,boolean autoScale,int limit,long expectedVersion) {
        com.sysadminanywhere.m3.base.persistence.Revisions.check(repository.findById(id).orElseThrow().getVersion(),expectedVersion);
        return updateCapacity(id,replicas,min,max,autoScale,limit);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public RuleWorkerPool updateCapacity(Long id, int replicas, int minReplicas, int maxReplicas,
                                         boolean autoScaleEnabled, int pendingJobsPerWorker) {
        var pool = repository.findById(id).orElseThrow();
        pool.setCapacity(replicas, minReplicas, maxReplicas, autoScaleEnabled, pendingJobsPerWorker);
        return repository.save(pool);
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.configure()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void delete(Long id) {
        var pool = repository.findById(id).orElseThrow();
        if ("default".equals(pool.getName())) {
            throw new IllegalStateException("The default worker pool cannot be deleted");
        }
        if (repository.existsByIdAndRulesIsNotEmpty(id)) {
            throw new IllegalStateException("Move rules to another pool before deleting this pool");
        }
        if (jobs.countByWorkerPool_IdAndStatusIn(id,java.util.List.of(com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.values())) > 0)
            throw new IllegalStateException("This pool still has job history or active deliveries and cannot be deleted");
        jdbc.update("DELETE FROM worker_pool_metric_sample WHERE worker_pool_id=?",id);
        repository.delete(pool);
    }

    @Transactional(readOnly = true)
    public boolean canDelete(Long id) {
        return !repository.existsByIdAndRulesIsNotEmpty(id)
                && jobs.countByWorkerPool_IdAndStatusIn(id,java.util.List.of(com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.values())) == 0;
    }

    @Transactional
    public void ensureDefaultPool() {
        var pool = repository.findByName("default")
                .orElseGet(() -> repository.save(new RuleWorkerPool("default", 1, 1, 4)));
        ruleRepository.assignUnassigned(pool.getId());
    }
}
