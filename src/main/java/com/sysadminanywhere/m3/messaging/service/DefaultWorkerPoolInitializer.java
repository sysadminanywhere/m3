package com.sysadminanywhere.m3.messaging.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class DefaultWorkerPoolInitializer implements ApplicationRunner {
    private final RuleWorkerPoolService workerPoolService;

    DefaultWorkerPoolInitializer(RuleWorkerPoolService workerPoolService) {
        this.workerPoolService = workerPoolService;
    }

    @Override
    public void run(ApplicationArguments args) {
        workerPoolService.ensureDefaultPool();
    }
}
