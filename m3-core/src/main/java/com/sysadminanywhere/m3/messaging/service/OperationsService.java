package com.sysadminanywhere.m3.messaging.service;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.time.Instant;
@Service @Profile("!worker")
public class OperationsService {
    public record Alert(String key,String severity,String detail,Instant firstSeen,Instant lastSeen){}
    public record Overdue(long messageId,String recipient,UUID attemptId,String status,Instant deadlineAt){}
    public record Snapshot(Instant sampledAt,Map<String,Long> metrics,List<Alert> alerts,List<Overdue> overdue){}
    private final JdbcTemplate jdbc;private final PayloadMasking masking;private final long databaseLimit;private final String localVolume;private final long minFree;
    private volatile Snapshot snapshot;
    private volatile boolean samplingFailed;
    public OperationsService(JdbcTemplate jdbc,PayloadMasking masking,@Value("${m3.storage.max-database-bytes:0}")long databaseLimit,
            @Value("${m3.storage.local-volume-path:}")String localVolume,@Value("${m3.storage.min-local-free-bytes:0}")long minFree){
        this.jdbc=jdbc;this.masking=masking;this.databaseLimit=databaseLimit;this.localVolume=localVolume;this.minFree=minFree;
        if(minFree<0||minFree>0&&localVolume.isBlank())throw new IllegalArgumentException("Free space monitoring requires a local volume path");
    }
    public Snapshot snapshot(){var value=snapshot;if(value==null){sample();value=snapshot;}return value;}
    public Snapshot refresh(){sample();return snapshot;}
    public boolean stale(Snapshot value){return samplingFailed||java.time.Duration.between(value.sampledAt(),Instant.now()).toMillis()>staleAfterMs;}
    @Value("${m3.operations.stale-after-ms:90000}") private long staleAfterMs=90000;
    @jakarta.annotation.PostConstruct void validate(){if(staleAfterMs<1)throw new IllegalArgumentException("Monitoring freshness threshold must be positive");}
    private long number(String sql,Object...args){var value=jdbc.queryForObject(sql,Long.class,args);return value==null?0:value;}
    @Scheduled(fixedDelayString="${m3.operations.sample-delay-ms:30000}",scheduler="maintenanceScheduler")
    public synchronized void sample(){try{collect();samplingFailed=false;}catch(RuntimeException failure){samplingFailed=true;throw failure;}}
    private void collect(){
        var m=new LinkedHashMap<String,Long>();
        m.put("m3_processing_overdue",number("SELECT count(*) FROM message_processing WHERE is_current AND overdue"));
        m.put("m3_processing_waiting",number("SELECT count(*) FROM message_processing WHERE is_current AND status IN ('LOADED','PROCESSING')"));
        m.put("m3_processing_failed",number("SELECT count(*) FROM message_processing WHERE is_current AND status='PROCESSING_FAILED'"));
        m.put("m3_processing_events_pending",number("SELECT count(*) FROM processing_event_outbox WHERE published_at IS NULL"));
        m.put("m3_processing_events_lag_seconds",number("SELECT coalesce(extract(epoch FROM now()-min(created_at))::bigint,0) FROM processing_event_outbox WHERE published_at IS NULL"));
        m.put("m3_archive_pending",number("SELECT count(*) FROM message_archive_job"));
        m.put("m3_archive_failures",number("SELECT count(*) FROM message_archive_job WHERE last_error IS NOT NULL"));
        m.put("m3_archive_lag_seconds",number("SELECT coalesce(extract(epoch FROM now()-min(m.processed_at))::bigint,0) FROM message_archive_job a JOIN message m USING(message_id)"));
        m.put("m3_index_pending",number("SELECT count(*) FROM message WHERE payload_bytes IS NOT NULL AND search_version IS DISTINCT FROM ?",masking.version()));
        m.put("m3_index_lag_seconds",number("SELECT coalesce(extract(epoch FROM now()-min(created_at))::bigint,0) FROM message WHERE payload_bytes IS NOT NULL AND search_version IS DISTINCT FROM ?",masking.version()));
        m.put("m3_storage_hot_bytes",number("SELECT hot_bytes FROM storage_usage WHERE singleton"));m.put("m3_storage_hot_limit_bytes",number("SELECT max_hot_bytes FROM storage_usage WHERE singleton"));
        m.put("m3_database_bytes",number("SELECT pg_database_size(current_database())"));m.put("m3_database_limit_bytes",databaseLimit);
        m.put("m3_cold_cleanup_pending",number("SELECT count(*) FROM cold_object_cleanup"));
        m.put("m3_worker_slots",number("SELECT count(*) FROM worker_slot WHERE expires_at>now()"));
        m.put("m3_routing_failed",number("SELECT count(*) FROM rule_execution_job WHERE status='FAILED'"));
        pressure("hot-storage",m.get("m3_storage_hot_bytes"),m.get("m3_storage_hot_limit_bytes"));pressure("database-storage",m.get("m3_database_bytes"),databaseLimit);
        alert("processing-event-backlog",m.get("m3_processing_events_lag_seconds")>60,"WARNING","Processing events are waiting for broker publication");
        alert("archive-failure",m.get("m3_archive_failures")>0,"WARNING","Archive failures retain hot data; inspect storage connectivity");
        alert("index-backlog",m.get("m3_index_lag_seconds")>60,"WARNING","Payload search indexing is delayed");
        if(!localVolume.isBlank()) {
            try{long free=java.nio.file.Files.getFileStore(java.nio.file.Path.of(localVolume)).getUsableSpace();m.put("m3_local_volume_free_bytes",free);alert("local-volume-space",minFree>0&&free<minFree,"CRITICAL","Configured local volume has insufficient free space");alert("local-volume-unavailable",false,"WARNING","");jdbc.update("UPDATE storage_usage SET admission_blocked=? WHERE singleton",minFree>0&&free<minFree);}
            catch(Exception error){alert("local-volume-unavailable",true,"WARNING","Configured local volume cannot be inspected");if(minFree>0)jdbc.update("UPDATE storage_usage SET admission_blocked=true WHERE singleton");}
        }else{
            jdbc.update("UPDATE storage_usage SET admission_blocked=false WHERE singleton");
        }
        var alerts=jdbc.query("SELECT * FROM operational_alert WHERE resolved_at IS NULL ORDER BY first_seen LIMIT 100",(rs,row)->new Alert(rs.getString("alert_key"),rs.getString("severity"),rs.getString("detail"),rs.getTimestamp("first_seen").toInstant(),rs.getTimestamp("last_seen").toInstant()));
        var overdue=jdbc.query("SELECT message_id,recipient,attempt_id,status,deadline_at FROM message_processing WHERE is_current AND overdue ORDER BY deadline_at LIMIT 100",(rs,row)->new Overdue(rs.getLong(1),rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getTimestamp(5).toInstant()));
        snapshot=new Snapshot(Instant.now(),Collections.unmodifiableMap(m),List.copyOf(alerts),List.copyOf(overdue));
    }
    private void pressure(String key,long used,long limit){alert(key,limit>0&&used>=limit*0.8,limit>0&&used>=limit*0.95?"CRITICAL":"WARNING","Storage is approaching the configured quota");}
    private void alert(String key,boolean active,String severity,String detail){
        if(active)jdbc.update("INSERT INTO operational_alert(alert_key,severity,detail) VALUES(?,?,?) ON CONFLICT(alert_key) DO UPDATE SET severity=excluded.severity,detail=excluded.detail,last_seen=now(),resolved_at=NULL",key,severity,detail);
        else jdbc.update("UPDATE operational_alert SET resolved_at=now() WHERE alert_key=? AND resolved_at IS NULL",key);
    }
    public String prometheus(){var value=snapshot();var text=new StringBuilder();value.metrics().forEach((name,number)->text.append("# TYPE ").append(name).append(" gauge\n").append(name).append(' ').append(number).append('\n'));
        text.append("# TYPE m3_operations_sample_age_seconds gauge\nm3_operations_sample_age_seconds ").append(Math.max(0,java.time.Duration.between(value.sampledAt(),Instant.now()).getSeconds())).append('\n');
        text.append("# TYPE m3_operations_data_stale gauge\nm3_operations_data_stale ").append(stale(value)?1:0).append('\n');return text.toString();}
}
