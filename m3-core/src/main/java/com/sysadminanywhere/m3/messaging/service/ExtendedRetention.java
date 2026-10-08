package com.sysadminanywhere.m3.messaging.service;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
@Service @Profile("!worker")
public class ExtendedRetention {
    private final JdbcTemplate jdbc;private final ColdPayloadStore cold;private final TransactionTemplate transactions;
    private final int catalogDays,errorDays,eventDays,historyDays;private final String errorMode;
    public ExtendedRetention(JdbcTemplate jdbc,ColdPayloadStore cold,PlatformTransactionManager manager,
            @Value("${m3.storage.catalog-days:0}") int catalogDays,@Value("${m3.storage.error-days:0}") int errorDays,
            @Value("${m3.storage.error-mode:KEEP}") String errorMode,@Value("${m3.retention.processing-events-days:30}") int eventDays,
            @Value("${m3.retention.message-history-days:0}") int historyDays) {
        this.jdbc=jdbc;this.cold=cold;transactions=new TransactionTemplate(manager);this.catalogDays=catalogDays;this.errorDays=errorDays;this.errorMode=errorMode.toUpperCase(Locale.ROOT);this.eventDays=eventDays;this.historyDays=historyDays;
        if(!Set.of("KEEP","ARCHIVE","DELETE").contains(this.errorMode)||catalogDays<0||errorDays<0||eventDays<0||historyDays<0)throw new IllegalArgumentException("Invalid retention policy");
    }
    private static final String SAFE="""
            NOT EXISTS(SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id AND j.status IN ('PENDING','PROCESSING'))
            AND NOT EXISTS(SELECT 1 FROM message_processing p WHERE p.message_id=m.message_id AND p.is_current AND p.status IN ('LOADED','PROCESSING'))
            AND NOT EXISTS(SELECT 1 FROM message_receipt_outbox o WHERE o.message_id=m.message_id AND o.published_at IS NULL)
            AND NOT EXISTS(SELECT 1 FROM processing_event_outbox o WHERE o.message_id=m.message_id AND o.published_at IS NULL)
            AND NOT EXISTS(SELECT 1 FROM message_metadata md WHERE md.key='sourceMessageId' AND md.value=m.message_id::text)
            """;
    @Scheduled(fixedDelay=60000,scheduler="maintenanceScheduler")
    public void clean(){
        if(catalogDays>0)remove("m.status IN ('SENT','PROCESSED') AND m.processed_at<now()-(?*interval '1 day') AND NOT EXISTS(SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id AND j.status='FAILED')",catalogDays);
        if(errorDays>0&&errorMode.equals("DELETE"))remove("m.status IN ('FAILED','PROCESSING_FAILED') AND coalesce(m.processed_at,m.created_at)<now()-(?*interval '1 day')",errorDays);
        if(eventDays>0)jdbc.update("DELETE FROM processing_event_outbox WHERE event_id IN(SELECT event_id FROM processing_event_outbox WHERE published_at<now()-(?*interval '1 day') LIMIT 500)",eventDays);
        if(historyDays>0)jdbc.update("DELETE FROM message_event WHERE event_id IN(SELECT e.event_id FROM message_event e JOIN message m ON m.message_id=e.message_id WHERE e.occurred_at<now()-(?*interval '1 day') AND m.status IN ('SENT','PROCESSED') AND "+SAFE+" LIMIT 500)",historyDays);
        if(!cold.configured())return;
        var keys=jdbc.queryForList("SELECT object_key FROM cold_object_cleanup WHERE next_attempt_at<=now() ORDER BY created_at LIMIT 25",String.class);
        for(String key:keys){
            // Cancel stale cleanup after restore without racing a later catalog deletion.
            boolean referenced=Boolean.TRUE.equals(transactions.execute(tx->{
                var live=jdbc.queryForList("SELECT message_id FROM message WHERE archive_key=? FOR SHARE",Long.class,key);
                if(live.isEmpty())return false;
                jdbc.update("DELETE FROM cold_object_cleanup WHERE object_key=?",key);
                return true;
            }));
            if(referenced)continue;
            try{cold.delete(key);jdbc.update("DELETE FROM cold_object_cleanup WHERE object_key=?",key);}
            catch(Exception failure){jdbc.update("UPDATE cold_object_cleanup SET attempts=attempts+1,last_error=?,next_attempt_at=now()+interval '5 minutes' WHERE object_key=?",failure.getClass().getSimpleName(),key);}
        }
    }
    private void remove(String condition,int days){
        var ids=jdbc.queryForList("SELECT m.message_id FROM message m WHERE "+condition+" AND "+SAFE+" ORDER BY m.message_id LIMIT 100",Long.class,days);
        for(Long id:ids)transactions.executeWithoutResult(tx->{
            jdbc.query("SELECT job_id FROM rule_execution_job WHERE message_id=? ORDER BY job_id FOR UPDATE",rs->{},id);
            var eligible=jdbc.queryForList("SELECT m.message_id FROM message m WHERE m.message_id=? AND "+condition+" AND "+SAFE+" FOR UPDATE OF m",Long.class,id,days);
            if(eligible.isEmpty())return;jdbc.update("DELETE FROM rule_execution_job WHERE message_id=?",id);jdbc.update("DELETE FROM message_metadata WHERE message_id=?",id);jdbc.update("DELETE FROM message WHERE message_id=?",id);
        });
    }
}
