package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository jobs;
    @Mock private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock private jakarta.persistence.EntityManager entityManager;

    @InjectMocks
    private MessageService messageService;

    private Message testMessage;

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(messageService, "entityManager", entityManager);
        testMessage = new Message(MessageDirection.INBOUND, "{\"data\": \"test\"}", "application/json");
    }

    @Test
    void findById_WhenMessageExists_ShouldReturnMessage() {
        // Given
        when(messageRepository.findById(1L)).thenReturn(Optional.of(testMessage));

        // When
        Message result = messageService.findById(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getPayload()).isEqualTo("{\"data\": \"test\"}");
    }

    @Test
    void findById_WhenMessageNotExists_ShouldReturnNull() {
        // Given
        when(messageRepository.findById(1L)).thenReturn(Optional.empty());

        // When
        Message result = messageService.findById(1L);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void list_ShouldReturnPageOfMessages() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Message> page = new PageImpl<>(List.of(testMessage), pageable, 1);
        when(messageRepository.findAll(pageable)).thenReturn(page);

        // When
        Page<Message> result = messageService.list(pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findByDirection_ShouldReturnFilteredMessages() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Message> page = new PageImpl<>(List.of(testMessage), pageable, 1);
        when(messageRepository.findByDirection(MessageDirection.INBOUND, pageable)).thenReturn(page);

        // When
        Page<Message> result = messageService.findByDirection(MessageDirection.INBOUND, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void findByStatus_ShouldReturnFilteredMessages() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Message pendingMessage = new Message(MessageDirection.INBOUND, "payload", "text/plain");
        Page<Message> page = new PageImpl<>(List.of(pendingMessage), pageable, 1);
        when(messageRepository.findByStatus(MessageStatus.PENDING, pageable)).thenReturn(page);

        // When
        Page<Message> result = messageService.findByStatus(MessageStatus.PENDING, pageable);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void deleteMessage_ShouldCallRepositoryDelete() {
        // When
        when(messageRepository.findById(1L)).thenReturn(Optional.of(testMessage));
        messageService.deleteMessage(1L);

        // Then
        verify(messageRepository, times(1)).delete(testMessage);
    }

    @Test
    void setPayload_WithExceedingMaxLength_ShouldThrowException() {
        // Given
        Message message = new Message(MessageDirection.INBOUND, "initial", "text/plain");
        String longPayload = "a".repeat(1_000_001);

        // Then
        assertThatThrownBy(() -> message.setPayload(longPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payload length exceeds");
    }

    @Test
    void setPayloadType_WithExceedingMaxLength_ShouldThrowException() {
        // Given
        Message message = new Message(MessageDirection.INBOUND, "payload", "text/plain");
        String longType = "a".repeat(256);

        // Then
        assertThatThrownBy(() -> message.setPayloadType(longType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payload type length exceeds");
    }
    @Test void uncertainDeliveryNeedsExplicitAcknowledgementBeforeManualRetry() {
        var channel=new com.sysadminanywhere.m3.messaging.domain.ChannelSettings("target",com.sysadminanywhere.m3.messaging.domain.ChannelType.DIRECTORY,com.sysadminanywhere.m3.messaging.domain.ChannelDirection.OUTBOUND);
        var rule=new com.sysadminanywhere.m3.messaging.domain.Rule("send",com.sysadminanywhere.m3.messaging.domain.RuleType.OUTBOUND,channel);
        var pool=new com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool("default",1,1,4); rule.setWorkerPool(pool);
        var job=new com.sysadminanywhere.m3.messaging.domain.RuleExecutionJob(testMessage,rule,pool);
        org.springframework.test.util.ReflectionTestUtils.setField(job,"id",1L);
        job.claim("worker"); job.markDeliveryStarted(); job.uncertain(new IllegalStateException("Uncertain"));
        testMessage.setStatus(MessageStatus.FAILED);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(testMessage));
        when(jobs.findByMessage_IdOrderByRule_PriorityAsc(1L)).thenReturn(List.of(job));
        assertThatThrownBy(()->messageService.retry(1L)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("acknowledge");
        assertThat(job.getStatus()).isEqualTo(com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.FAILED);
        messageService.retry(1L,true);
        assertThat(job.getStatus()).isEqualTo(com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.PENDING);
        assertThat(job.isDeliveryUncertain()).isFalse();
    }
}
