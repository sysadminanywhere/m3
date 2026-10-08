package com.sysadminanywhere.m3.messaging.service;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration @Profile("!worker")
public class MaintenanceConfiguration {
    @Bean("VaadinTaskExecutor") public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor vaadinTaskExecutor() {
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();executor.setCorePoolSize(2);executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);executor.setThreadNamePrefix("vaadin-ui-");return executor;
    }
    @Bean("taskScheduler") public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("control-scheduler-");return scheduler;
    }
    @Bean("maintenanceScheduler")
    public ThreadPoolTaskScheduler maintenanceScheduler() {
        var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("message-maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
