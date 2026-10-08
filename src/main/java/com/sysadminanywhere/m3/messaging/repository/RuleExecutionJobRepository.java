package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.RuleExecutionJob;
import com.sysadminanywhere.m3.messaging.domain.RuleJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RuleExecutionJobRepository extends JpaRepository<RuleExecutionJob, Long> {
    boolean existsByRule_Id(Long ruleId);
    @Query(value="select count(*) from rule_execution_job j join rule r on r.rule_id=j.rule_id " +
            "where (j.status='PENDING' and r.worker_pool_id=:poolId) or (j.status='PROCESSING' and j.worker_pool_id=:poolId)",nativeQuery=true)
    long queueDepth(@Param("poolId") Long poolId);
    long countByMessage_IdAndStatusIn(Long messageId, Iterable<RuleJobStatus> statuses);
    long countByMessage_IdAndStatus(Long messageId, RuleJobStatus status);
    java.util.List<RuleExecutionJob> findByMessage_IdOrderByRule_PriorityAsc(Long messageId);
    long countByWorkerPool_IdAndStatusIn(Long workerPoolId, java.util.Collection<RuleJobStatus> statuses);

    @Modifying
    @Query(value = "update rule_execution_job set status = 'PENDING', worker_id = null, claimed_at = null, claim_token = null " +
            "where status = 'PROCESSING' and delivery_started=false and worker_pool_id = (select worker_pool_id from rule_worker_pool where name = :pool) " +
            "and claimed_at < now() - (:leaseSeconds * interval '1 second')", nativeQuery = true)
    int releaseExpiredClaims(@Param("pool") String pool, @Param("leaseSeconds") int leaseSeconds);

    @Modifying
    @Query(value = "update rule_execution_job set worker_pool_id = :poolId " +
            "where rule_id = :ruleId and status = 'PENDING'", nativeQuery = true)
    int moveUnfinishedJobs(@Param("ruleId") Long ruleId, @Param("poolId") Long poolId);
}
