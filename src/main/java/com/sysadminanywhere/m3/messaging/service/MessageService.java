package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final MessageReceiptOutbox receipts;

    MessageService(MessageRepository messageRepository, MessageReceiptOutbox receipts) {
        this.messageRepository = messageRepository;
        this.receipts = receipts;
    }

    @Transactional
    public Message createMessage(MessageDirection direction, String payload, String payloadType) {
        var message = new Message(direction, payload, payloadType);
        var saved = messageRepository.save(message);
        if (direction == MessageDirection.INBOUND) receipts.record(saved.getId());
        return saved;
    }

    @Transactional
    public Message createMessage(MessageDirection direction, String payload, String payloadType,
                                  @Nullable String sourceSystem, @Nullable String targetSystem) {
        var message = new Message(direction, payload, payloadType);
        message.setSourceSystem(sourceSystem);
        message.setTargetSystem(targetSystem);
        var saved = messageRepository.save(message);
        if (direction == MessageDirection.INBOUND) receipts.record(saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Message findById(Long id) {
        return messageRepository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public Page<Message> list(Pageable pageable) {
        return messageRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<Message> findByDirection(MessageDirection direction, Pageable pageable) {
        return messageRepository.findByDirection(direction, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Message> findByStatus(MessageStatus status, Pageable pageable) {
        return messageRepository.findByStatus(status, pageable);
    }

    @Transactional
    public void updateStatus(Long messageId, MessageStatus status) {
        var message = messageRepository.findById(messageId).orElseThrow();
        message.setStatus(status);
        if (status == MessageStatus.PROCESSED || status == MessageStatus.SENT) {
            message.setProcessedAt(Instant.now());
        }
        messageRepository.save(message);
    }

    @Transactional
    public void deleteMessage(Long id) {
        messageRepository.deleteById(id);
    }
}
