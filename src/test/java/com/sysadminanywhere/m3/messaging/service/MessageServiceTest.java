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
    private MessageReceiptOutbox receipts;

    @InjectMocks
    private MessageService messageService;

    private Message testMessage;

    @BeforeEach
    void setUp() {
        testMessage = new Message(MessageDirection.INBOUND, "{\"data\": \"test\"}", "application/json");
    }

    @Test
    void createMessage_WithBasicParams_ShouldSaveMessage() {
        // Given
        when(messageRepository.save(any(Message.class))).thenReturn(testMessage);

        // When
        Message result = messageService.createMessage(MessageDirection.INBOUND, "{\"data\": \"test\"}", "application/json");

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getDirection()).isEqualTo(MessageDirection.INBOUND);
        assertThat(result.getPayload()).isEqualTo("{\"data\": \"test\"}");
        assertThat(result.getPayloadType()).isEqualTo("application/json");
        assertThat(result.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(result.getCreatedAt()).isNotNull();
        verify(messageRepository, times(1)).save(any(Message.class));
    }

    @Test
    void createMessage_WithSourceAndTarget_ShouldSaveMessageWithSystems() {
        // Given
        Message outboundMessage = new Message(MessageDirection.OUTBOUND, "payload", "text/plain");
        outboundMessage.setSourceSystem("SourceSystem");
        outboundMessage.setTargetSystem("TargetSystem");
        when(messageRepository.save(any(Message.class))).thenReturn(outboundMessage);

        // When
        Message result = messageService.createMessage(
                MessageDirection.OUTBOUND,
                "payload",
                "text/plain",
                "SourceSystem",
                "TargetSystem"
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getDirection()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(result.getSourceSystem()).isEqualTo("SourceSystem");
        assertThat(result.getTargetSystem()).isEqualTo("TargetSystem");
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
    void updateStatus_ToProcessed_ShouldSetProcessedAt() {
        // Given
        Message message = spy(new Message(MessageDirection.INBOUND, "payload", "text/plain"));
        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));
        when(messageRepository.save(any(Message.class))).thenReturn(message);

        // When
        messageService.updateStatus(1L, MessageStatus.PROCESSED);

        // Then
        verify(message).setStatus(MessageStatus.PROCESSED);
        verify(message).setProcessedAt(any(Instant.class));
        verify(messageRepository).save(message);
    }

    @Test
    void updateStatus_ToSent_ShouldSetProcessedAt() {
        // Given
        Message message = spy(new Message(MessageDirection.INBOUND, "payload", "text/plain"));
        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));
        when(messageRepository.save(any(Message.class))).thenReturn(message);

        // When
        messageService.updateStatus(1L, MessageStatus.SENT);

        // Then
        verify(message).setProcessedAt(any(Instant.class));
    }

    @Test
    void updateStatus_ToOtherStatus_ShouldNotSetProcessedAt() {
        // Given
        Message message = spy(new Message(MessageDirection.INBOUND, "payload", "text/plain"));
        when(messageRepository.findById(1L)).thenReturn(Optional.of(message));
        when(messageRepository.save(any(Message.class))).thenReturn(message);

        // When
        messageService.updateStatus(1L, MessageStatus.FAILED);

        // Then
        verify(message, never()).setProcessedAt(any());
    }

    @Test
    void deleteMessage_ShouldCallRepositoryDelete() {
        // When
        messageService.deleteMessage(1L);

        // Then
        verify(messageRepository, times(1)).deleteById(1L);
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
        String longType = "a".repeat(51);

        // Then
        assertThatThrownBy(() -> message.setPayloadType(longType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payload type length exceeds");
    }
}
