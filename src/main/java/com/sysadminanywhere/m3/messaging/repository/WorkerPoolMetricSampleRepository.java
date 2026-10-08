package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.WorkerPoolMetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface WorkerPoolMetricSampleRepository extends JpaRepository<WorkerPoolMetricSample, Long> {
    Optional<WorkerPoolMetricSample> findTopByWorkerPool_IdOrderBySampledAtDesc(Long poolId);

    @Query("select avg(s.cpuPercent) from WorkerPoolMetricSample s where s.workerPool.id = :poolId")
    Double averageCpu(@Param("poolId") Long poolId);

    @Query("select max(s.cpuPercent) from WorkerPoolMetricSample s where s.workerPool.id = :poolId")
    Double maximumCpu(@Param("poolId") Long poolId);

    @Query("select avg(s.memoryPercent) from WorkerPoolMetricSample s where s.workerPool.id = :poolId")
    Double averageMemory(@Param("poolId") Long poolId);

    @Query("select max(s.memoryPercent) from WorkerPoolMetricSample s where s.workerPool.id = :poolId")
    Double maximumMemory(@Param("poolId") Long poolId);

    @Modifying
    @Query("delete from WorkerPoolMetricSample s where s.sampledAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
