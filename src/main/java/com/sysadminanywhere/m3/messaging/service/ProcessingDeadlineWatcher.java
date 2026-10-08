package com.sysadminanywhere.m3.messaging.service;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
@Component @Profile("!worker")
public class ProcessingDeadlineWatcher {
    private final ExternalProcessingService processing;
    public ProcessingDeadlineWatcher(ExternalProcessingService processing){this.processing=processing;}
    @Scheduled(fixedDelayString="${m3.processing.watch-delay-ms:30000}",scheduler="maintenanceScheduler")
    public void check(){processing.detectOverdue();}
}
