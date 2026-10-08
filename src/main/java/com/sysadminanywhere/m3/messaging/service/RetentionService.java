package com.sysadminanywhere.m3.messaging.service;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.*;

/** Bounded cleanup, opt-in because stored business messages must not be silently discarded on upgrade. */
@Service @Profile("!worker")
public class RetentionService {
    private static final Logger log=LoggerFactory.getLogger(RetentionService.class);
    private final JdbcTemplate jdbc;
    private final int messagesDays,receiptsDays,keysDays,auditDays;
    @Value("${m3.storage.mode:KEEP}") private String storageMode="KEEP";
    public RetentionService(JdbcTemplate jdbc,@Value("${m3.retention.messages-days:0}") int messagesDays,
            @Value("${m3.retention.receipts-days:30}") int receiptsDays,@Value("${m3.retention.idempotency-days:0}") int keysDays,
            @Value("${m3.retention.audit-days:365}") int auditDays) {
        this.jdbc=jdbc; this.messagesDays=messagesDays; this.receiptsDays=receiptsDays; this.keysDays=keysDays; this.auditDays=auditDays;
        if(java.util.stream.IntStream.of(messagesDays,receiptsDays,keysDays,auditDays).anyMatch(days->days<0)) throw new IllegalArgumentException("Retention days must be nonnegative");
    }
    @Scheduled(fixedDelay=21600000,initialDelay=60000) @Transactional
    public void clean() {
        int removed=0;
        if(messagesDays>0 && "KEEP".equalsIgnoreCase(storageMode)) {
            var ids=jdbc.queryForList("""
                    SELECT m.message_id FROM message m WHERE m.status IN ('SENT','PROCESSED')
                      AND m.archive_key IS NULL
                      AND m.processed_at < now()-(? * interval '1 day')
                      AND NOT EXISTS(SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id AND j.status IN ('PENDING','PROCESSING','FAILED'))
                      AND NOT EXISTS(SELECT 1 FROM message_receipt_outbox o WHERE o.message_id=m.message_id AND o.published_at IS NULL)
                      AND NOT EXISTS(SELECT 1 FROM processing_event_outbox o WHERE o.message_id=m.message_id AND o.published_at IS NULL)
                      AND NOT EXISTS(SELECT 1 FROM message_processing p WHERE p.message_id=m.message_id AND p.is_current AND p.status IN ('LOADED','PROCESSING'))
                      AND NOT EXISTS(SELECT 1 FROM message_metadata md JOIN message copy ON copy.message_id=md.message_id
                        WHERE md.key='sourceMessageId' AND md.value=m.message_id::text)
                    ORDER BY m.message_id LIMIT 500 FOR UPDATE OF m SKIP LOCKED
                    """,Long.class,messagesDays);
            for(Long id:ids) {
                jdbc.update("DELETE FROM rule_execution_job WHERE message_id=?",id);
                jdbc.update("DELETE FROM message_metadata WHERE message_id=?",id);
                removed+=jdbc.update("DELETE FROM message WHERE message_id=?",id);
            }
        }
        if(receiptsDays>0) jdbc.update("DELETE FROM message_receipt_outbox WHERE event_id IN (SELECT event_id FROM message_receipt_outbox WHERE published_at < now()-(? * interval '1 day') LIMIT 500)",receiptsDays);
        if(keysDays>0) {
            // Receipt keys are retained indefinitely by default; shortening this defines a deduplication window.
            jdbc.update("DELETE FROM source_delivery_receipt WHERE ctid IN (SELECT ctid FROM source_delivery_receipt WHERE received_at < now()-(? * interval '1 day') AND NOT EXISTS(SELECT 1 FROM message m WHERE m.message_id=source_delivery_receipt.message_id AND m.status NOT IN ('SENT','PROCESSED')) LIMIT 500)",keysDays);
            jdbc.update("DELETE FROM outbound_message_request WHERE ctid IN (SELECT ctid FROM outbound_message_request WHERE created_at < now()-(? * interval '1 day') AND NOT EXISTS(SELECT 1 FROM message m WHERE m.message_id=outbound_message_request.message_id AND m.status NOT IN ('SENT','PROCESSED')) LIMIT 500)",keysDays);
        }
        if(auditDays>0) jdbc.update("DELETE FROM configuration_audit WHERE audit_id IN (SELECT audit_id FROM configuration_audit WHERE occurred_at < now()-(? * interval '1 day') LIMIT 500)",auditDays);
        if(removed>0) log.info("Retention removed {} completed messages",removed);
    }
}
