package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.PayloadCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Rebuildable, masked search projection; never part of the routing transaction. */
@Service @Profile("!worker")
public class MessageIndexService {
    private final JdbcTemplate jdbc;
    private final PayloadMasking masking;
    private final TransactionTemplate transactions;
    public MessageIndexService(JdbcTemplate jdbc, PayloadMasking masking, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.masking=masking; this.transactions=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${m3.search.index-delay-ms:2000}", scheduler="maintenanceScheduler")
    public void index() {
        transactions.executeWithoutResult(status -> {
            var ids=jdbc.queryForList("SELECT message_id FROM message WHERE search_version IS DISTINCT FROM ? ORDER BY message_id LIMIT 100 FOR UPDATE SKIP LOCKED",Long.class,masking.version());
            for (Long id:ids) {
                jdbc.query("SELECT payload_bytes,charset,payload_type FROM message WHERE message_id=?",rs -> {
                    String text=rs.getBytes(1)==null?null:masking.payload(rs.getBytes(1),rs.getString(2),rs.getString(3));
                    var correlation=jdbc.query("SELECT key,value FROM message_metadata WHERE message_id=? AND key IN ('correlationId','correlation_id','requestId') ORDER BY key LIMIT 1",(row,index)->masking.metadata(row.getString(1),row.getString(2)),id);
                    jdbc.update("UPDATE message SET search_text=?,search_version=?,correlation_id=? WHERE message_id=?",text,masking.version(),
                            correlation.isEmpty()?null:masking.text(correlation.getFirst()).substring(0,Math.min(200,masking.text(correlation.getFirst()).length())),id);
                },id);
            }
        });
    }
}
