package com.sysadminanywhere.m3.messaging.service;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @Profile("!worker")
public class MessageArchiveScheduler {
    private final MessageArchiveService archive;
    public MessageArchiveScheduler(MessageArchiveService archive) { this.archive=archive; }
    @Scheduled(fixedDelayString="${m3.archive.scan-delay-ms:60000}", scheduler="maintenanceScheduler")
    public void scan() { archive.enqueue(); }
    @Scheduled(fixedDelayString="${m3.archive.work-delay-ms:5000}", scheduler="maintenanceScheduler")
    public void work() { for(int i=0;i<10;i++) if(!archive.archiveNext()) break; }
}
