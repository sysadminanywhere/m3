package com.sysadminanywhere.m3.messaging.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rule_execution_job", indexes = {
        @Index(name = "idx_rule_job_pool_status_created", columnList = "worker_pool_id,status,created_at"),
        @Index(name = "idx_rule_job_pool_claim", columnList = "worker_pool_id,status,claimed_at"),
        @Index(name = "idx_rule_job_message", columnList = "message_id")
})
public class RuleExecutionJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "worker_pool_id", nullable = false)
    private RuleWorkerPool workerPool;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleJobStatus status = RuleJobStatus.PENDING;

    @Column(name = "worker_id", length = 160)
    private String workerId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "claim_token")
    private UUID claimToken;

    protected RuleExecutionJob() {}

    public RuleExecutionJob(Message message, Rule rule, RuleWorkerPool workerPool) {
        this.message = message;
        this.rule = rule;
        this.workerPool = workerPool;
    }

    public Long getId() { return id; }
    public Message getMessage() { return message; }
    public Rule getRule() { return rule; }
    public RuleWorkerPool getWorkerPool() { return workerPool; }
    public RuleJobStatus getStatus() { return status; }
    public String getWorkerId() { return workerId; }
    public String getErrorMessage() { return errorMessage; }
    public String getResult() { return result; }
    public Long getMessageId() { return message.getId(); }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public UUID getClaimToken() { return claimToken; }

    public void claim(String workerId) {
        this.status = RuleJobStatus.PROCESSING;
        this.workerId = workerId;
        this.claimedAt = Instant.now();
        this.claimToken = UUID.randomUUID();
        this.attempts++;
    }

    public void complete() {
        this.status = RuleJobStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.claimedAt = null;
        this.claimToken = null;
        this.errorMessage = null;
    }

    public void setResult(String result) { this.result = result; }
    public void reassignPool(RuleWorkerPool pool) {
        if (status == RuleJobStatus.PROCESSING) throw new IllegalStateException("Cannot reassign a running claim");
        this.workerPool = pool;
    }

    public void fail(Throwable error) {
        this.status = RuleJobStatus.FAILED;
        this.errorMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        this.completedAt = Instant.now();
        this.claimedAt = null;
        this.claimToken = null;
    }

    public void retry(Throwable error, long delaySeconds) {
        this.status = RuleJobStatus.PENDING;
        this.errorMessage = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        this.nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
        this.claimedAt = null;
        this.claimToken = null;
        this.workerId = null;
        this.completedAt = null;
    }

    public void retryNow() {
        retry(new IllegalStateException("Retry requested"), 0);
        this.attempts = 0;
    }
}
