package com.sysadminanywhere.m3.messaging.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MessageReceiptOutbox {
    private final JdbcTemplate jdbc;

    public MessageReceiptOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long messageId) {
        jdbc.update("INSERT INTO message_receipt_outbox(event_id, message_id) VALUES (?, ?)",
                UUID.randomUUID(), messageId);
    }
}
