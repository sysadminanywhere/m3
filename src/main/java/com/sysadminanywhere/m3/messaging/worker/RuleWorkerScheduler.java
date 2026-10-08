package com.sysadminanywhere.m3.messaging.worker;

import com.sysadminanywhere.m3.messaging.service.RuleExecutionProcessor;
import com.sysadminanywhere.m3.messaging.outbound.OutboundDeliveryWorker;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
@EnableScheduling
class RuleWorkerScheduler {
    private final RuleExecutionProcessor processor;
    private final OutboundDeliveryWorker outbound;

    RuleWorkerScheduler(RuleExecutionProcessor processor, OutboundDeliveryWorker outbound) { this.processor = processor; this.outbound = outbound; }

    @Scheduled(fixedDelayString = "${m3.worker.poll-delay-ms:250}")
    void poll() { processor.processNext(); }

    @Scheduled(fixedDelayString = "${m3.worker.poll-delay-ms:250}")
    void pollOutbound() { outbound.processNext(); }

    @Scheduled(fixedDelayString = "${m3.worker.reaper-delay-ms:60000}")
    void reclaimExpired() { processor.reclaimExpired(); }
}
