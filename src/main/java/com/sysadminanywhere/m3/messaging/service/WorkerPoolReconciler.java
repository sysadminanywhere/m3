package com.sysadminanywhere.m3.messaging.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!worker")
@EnableScheduling
@Order(Ordered.LOWEST_PRECEDENCE)
public class WorkerPoolReconciler implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkerPoolReconciler.class);
    private final WorkerPoolCapacityService capacityService;

    public WorkerPoolReconciler(WorkerPoolCapacityService capacityService) { this.capacityService = capacityService; }

    @Override
    public void run(ApplicationArguments args) { reconcile(); }

    @Scheduled(fixedDelayString = "${m3.worker.reconcile-delay-ms:30000}", initialDelayString = "${m3.worker.reconcile-delay-ms:30000}")
    public void reconcile() {
        try { capacityService.reconcileAll(); }
        catch (Exception e) { log.warn("Worker pool reconciliation failed; it will be retried", e); }
    }
}
