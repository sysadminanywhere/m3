package com.sysadminanywhere.m3.messaging.outbound;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.*;
import com.sysadminanywhere.m3.messaging.service.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DeliveryFailureTest {
    @Test void retriesBeforeSendingButStopsWhenTheReceiverMayHaveAccepted() {
        var message=new Message(MessageDirection.OUTBOUND,"test","text/plain");
        var channel=new ChannelSettings("target",ChannelType.DIRECTORY,ChannelDirection.OUTBOUND);
        var rule=new Rule("send",RuleType.OUTBOUND,channel);
        rule.setWorkerPool(new RuleWorkerPool("default",1,1,4));
        var job=new RuleExecutionJob(message,rule,rule.getWorkerPool());
        job.claim("worker");
        var em=mock(EntityManager.class);
        when(em.find(eq(RuleExecutionJob.class),eq(1L),any(jakarta.persistence.LockModeType.class))).thenReturn(job);
        var service=new OutboundDeliveryTransactions(mock(RuleExecutionJobRepository.class),mock(ChannelSettingsRepository.class),mock(RuleEngine.class),new ObjectMapper(),"default","worker",20);
        ReflectionTestUtils.setField(service,"entityManager",em);
        var claim=new OutboundDeliveryTransactions.Claim(1,2,job.getClaimToken(),null);
        service.failed(claim,new java.io.IOException("Connection refused"));
        assertThat(job.getStatus()).isEqualTo(RuleJobStatus.PENDING);
        assertThat(job.isDeliveryUncertain()).isFalse();
        job.claim("worker");
        claim=new OutboundDeliveryTransactions.Claim(1,2,job.getClaimToken(),null);
        service.started(claim);
        service.failed(claim,new java.io.IOException("Connection lost after write"));
        assertThat(job.getStatus()).isEqualTo(RuleJobStatus.FAILED);
        assertThat(job.isDeliveryUncertain()).isTrue();
        assertThat(message.getStatus()).isEqualTo(MessageStatus.FAILED);
        job.retryNow();
        assertThat(job.isDeliveryStarted()).isFalse();
        assertThat(job.isDeliveryUncertain()).isFalse();
    }
    @Test void deliveryMarkerRunsOnceAndDoesNotLeakAcrossAttempts() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        DeliveryAttempt.run(calls::incrementAndGet,()->{DeliveryAttempt.started();DeliveryAttempt.started();});
        assertThat(calls).hasValue(1);
        DeliveryAttempt.started();
        assertThat(calls).hasValue(1);
    }
}
