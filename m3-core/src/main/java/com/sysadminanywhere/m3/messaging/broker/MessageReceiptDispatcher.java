package com.sysadminanywhere.m3.messaging.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MessageReceiptDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MessageReceiptDispatcher.class);
    private final JdbcTemplate jdbc;
    private final MessageReceivedPublisher publisher;

    public MessageReceiptDispatcher(JdbcTemplate jdbc, MessageReceivedPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${m3.receipts.poll-delay-ms:1000}")
    @Transactional
    public void dispatch() {
        var events = jdbc.query("""
                SELECT event_id, message_id, created_at FROM message_receipt_outbox
                WHERE published_at IS NULL AND next_attempt_at <= now()
                ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new MessageReceivedEvent(rs.getObject("event_id", UUID.class),
                "message.received", 1, rs.getLong("message_id"), rs.getTimestamp("created_at").toInstant(),
                "/api/v1/messages/" + rs.getLong("message_id")));
        for (var event : events) {
            try {
                publisher.publish(event);
                jdbc.update("UPDATE message_receipt_outbox SET published_at=now(), attempts=attempts+1, last_error=NULL WHERE event_id=?",
                        event.eventId());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                String error = exception.toString();
                jdbc.update("""
                        UPDATE message_receipt_outbox SET attempts=attempts+1, last_error=?,
                        next_attempt_at=now() + interval '10 seconds' WHERE event_id=?
                        """, error.substring(0, Math.min(error.length(), 2000)), event.eventId());
                log.warn("Receipt event {} could not be published; retry scheduled", event.eventId(), exception);
                break;
            }
        }
    }
}
