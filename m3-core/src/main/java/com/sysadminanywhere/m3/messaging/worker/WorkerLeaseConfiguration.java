package com.sysadminanywhere.m3.messaging.worker;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration @Profile("worker")
public class WorkerLeaseConfiguration {
    @Bean("workerLeaseScheduler") public ThreadPoolTaskScheduler workerLeaseScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("worker-lease-");return scheduler;
    }
    @Bean("taskScheduler") public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("worker-jobs-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(60);return scheduler;
    }
}
