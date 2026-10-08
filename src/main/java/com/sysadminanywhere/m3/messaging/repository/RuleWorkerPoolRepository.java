package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RuleWorkerPoolRepository extends JpaRepository<RuleWorkerPool, Long> {
    Optional<RuleWorkerPool> findByName(String name);
    boolean existsByName(String name);
    boolean existsByIdAndRulesIsNotEmpty(Long id);
}
