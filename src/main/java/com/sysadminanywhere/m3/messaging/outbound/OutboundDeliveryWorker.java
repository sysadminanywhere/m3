package com.sysadminanywhere.m3.messaging.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OutboundDeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryWorker.class);
    private final OutboundDeliveryTransactions transactions;
    private final OutboundChannelSender sender;
    public OutboundDeliveryWorker(OutboundDeliveryTransactions transactions, OutboundChannelSender sender) { this.transactions = transactions; this.sender = sender; }
    public boolean processNext() {
        var claim = transactions.claim();
        if (claim == null) return false;
        try {
            sender.send(claim.messageId(), claim.delivery());
            transactions.succeeded(claim);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            transactions.failed(claim, error);
            log.warn("Outbound message {} could not be delivered ({})", claim.messageId(), error.getClass().getSimpleName());
        }
        return true;
    }
}
