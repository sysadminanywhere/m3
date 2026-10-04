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
    private final com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository jobs;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    public MessageService(MessageRepository messageRepository,
            com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository jobs, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.messageRepository = messageRepository;
        this.jobs = jobs;
        this.jdbc = jdbc;
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
    public Page<Message> findByDirection(MessageDirection direction, @Nullable MessageStatus status, Pageable pageable) {
        return status == null ? messageRepository.findByDirection(direction, pageable)
                : messageRepository.findByDirectionAndStatus(direction, status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Message> findByStatus(MessageStatus status, Pageable pageable) {
        return messageRepository.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public List<JobInfo> executionJobs(Long messageId) {
        return jobs.findByMessage_IdOrderByRule_PriorityAsc(messageId).stream().map(job -> new JobInfo(
                job.getId(),job.getRule().getId(),job.getWorkerPool().getName(),job.getStatus(),
                job.getAttempts(),job.getNextAttemptAt(),job.getErrorMessage())).toList();
    }
    public record JobInfo(Long id,Long ruleId,String pool,com.sysadminanywhere.m3.messaging.domain.RuleJobStatus status,
                          int attempts,Instant nextAttemptAt,String error) { }

    private Message lockMessageAndJobs(Long id) {
        var message = messageRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Message not found"));
        var items = jobs.findByMessage_IdOrderByRule_PriorityAsc(id);
        items.stream().sorted(java.util.Comparator.comparing(com.sysadminanywhere.m3.messaging.domain.RuleExecutionJob::getId))
                .forEach(job -> entityManager.refresh(job,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        entityManager.refresh(message,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return message;
    }

    @Transactional
    public void retry(Long id) {
        var message = lockMessageAndJobs(id);
        if (message.getStatus() != MessageStatus.FAILED) throw new IllegalArgumentException("Only failed messages can be retried");
        var items = jobs.findByMessage_IdOrderByRule_PriorityAsc(id);
        var failed = items.stream().filter(job -> job.getStatus() == com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.FAILED).toList();
        if (failed.isEmpty()) throw new IllegalArgumentException("Message has no failed execution job; resubmit with an explicit ruleId");
        for (var job : failed) {
            if (!Boolean.TRUE.equals(job.getRule().getEnabled()) || job.getRule().getWorkerPool() == null)
                throw new IllegalArgumentException("Enable the rule and assign a worker pool before retrying");
            if (message.getDirection() == MessageDirection.INBOUND) job.setResult(null);
            job.reassignPool(job.getRule().getWorkerPool());
            job.retryNow();
        }
        message.setStatus(MessageStatus.PENDING);
        message.setProcessedAt(null);
    }

    @Transactional
    public void deleteMessage(Long id) {
        var message = lockMessageAndJobs(id);
        var items = jobs.findByMessage_IdOrderByRule_PriorityAsc(id);
        if (items.stream().anyMatch(job -> job.getStatus() == com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.PENDING
                || job.getStatus() == com.sysadminanywhere.m3.messaging.domain.RuleJobStatus.PROCESSING))
            throw new IllegalArgumentException("Message still has pending or running jobs");
        Long unpublished = jdbc.queryForObject("SELECT count(*) FROM message_receipt_outbox WHERE message_id=? AND published_at IS NULL",Long.class,id);
        if (unpublished != null && unpublished > 0) throw new IllegalArgumentException("Wait for receipt notification publication before deleting the message");
        jobs.deleteAll(items);
        jobs.flush();
        messageRepository.delete(message);
    }
}
