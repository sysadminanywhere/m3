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

    public RuleWorkerPoolService(RuleWorkerPoolRepository repository, RuleRepository ruleRepository) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
    }

    @Transactional(readOnly = true)
    public List<RuleWorkerPool> findAll() {
        return repository.findAll().stream().sorted(java.util.Comparator.comparing(RuleWorkerPool::getName)).toList();
    }

    @Transactional
    public RuleWorkerPool create(String name, int replicas, int minReplicas, int maxReplicas,
                                 boolean autoScaleEnabled, int pendingJobsPerWorker) {
        if (repository.existsByName(name)) throw new IllegalArgumentException("Worker pool already exists: " + name);
        return repository.save(new RuleWorkerPool(name, replicas, minReplicas, maxReplicas,
                autoScaleEnabled, pendingJobsPerWorker));
    }

    @Transactional
    public RuleWorkerPool updateCapacity(Long id, int replicas, int minReplicas, int maxReplicas,
                                         boolean autoScaleEnabled, int pendingJobsPerWorker) {
        var pool = repository.findById(id).orElseThrow();
        pool.setCapacity(replicas, minReplicas, maxReplicas, autoScaleEnabled, pendingJobsPerWorker);
        return repository.save(pool);
    }

    @Transactional
    public void delete(Long id) {
        var pool = repository.findById(id).orElseThrow();
        if ("default".equals(pool.getName())) {
            throw new IllegalStateException("The default worker pool cannot be deleted");
        }
        if (repository.existsByIdAndRulesIsNotEmpty(id)) {
            throw new IllegalStateException("Move rules to another pool before deleting this pool");
        }
        repository.delete(pool);
    }

    @Transactional
    public void ensureDefaultPool() {
        var pool = repository.findByName("default")
                .orElseGet(() -> repository.save(new RuleWorkerPool("default", 1, 1, 4)));
        for (var rule : ruleRepository.findAll()) {
            if (rule.getWorkerPool() == null) rule.setWorkerPool(pool);
        }
    }
}
