package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable two-phase archive: claim, upload + read-back, then conditional hot-payload removal. */
@Service
public class MessageArchiveService {
    public record Snapshot(long jobId,byte[] bytes,String type,String charset,String metadata,String sha256,int size) { }
    public record Envelope(int version,long messageId,byte[] payload,List<Snapshot> deliveries) { }
    private record Claim(long id,UUID token,String key,byte[] bytes,Envelope envelope) { }
    private final JdbcTemplate jdbc;
    private final ColdPayloadStore store;
    private final ObjectMapper json;
    private final PayloadMasking masking;
    private final TransactionTemplate transactions;
    private final String mode;
    private final int hotDays;
    @Value("${m3.storage.error-mode:KEEP}") private String errorMode="KEEP";
    @Value("${m3.storage.error-days:0}") private int errorDays;
    @jakarta.annotation.PostConstruct public void validateErrorPolicy(){if(errorDays<0 || !Set.of("KEEP","ARCHIVE","DELETE").contains(errorMode.toUpperCase(Locale.ROOT)) || "ARCHIVE".equalsIgnoreCase(errorMode)&&!mode.equals("ARCHIVE"))throw new IllegalArgumentException("Error ARCHIVE requires ARCHIVE storage mode; retention days must be nonnegative");}
    public MessageArchiveService(JdbcTemplate jdbc,ColdPayloadStore store,ObjectMapper json,PayloadMasking masking,
            PlatformTransactionManager manager,@Value("${m3.storage.mode:KEEP}") String mode,@Value("${m3.storage.hot-days:7}") int hotDays) {
        this.jdbc=jdbc;this.store=store;this.json=json;this.masking=masking;this.transactions=new TransactionTemplate(manager);
        this.mode=mode.toUpperCase(Locale.ROOT);this.hotDays=hotDays;
        if(!Set.of("KEEP","ARCHIVE","DELETE").contains(this.mode)||hotDays<1) throw new IllegalArgumentException("Invalid message storage policy");
        if(this.mode.equals("ARCHIVE")&&!store.configured()) throw new IllegalArgumentException("ARCHIVE policy requires configured object storage");
    }
    public String mode() { return mode; }
    public int hotDays() { return hotDays; }
    private static final String ELIGIBLE="""
            m.status IN ('SENT','PROCESSED') AND m.payload_bytes IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM rule_execution_job j WHERE j.message_id=m.message_id AND j.status IN ('PENDING','PROCESSING','FAILED'))
            AND NOT EXISTS(SELECT 1 FROM message_receipt_outbox o WHERE o.message_id=m.message_id AND o.published_at IS NULL)
            AND NOT EXISTS(SELECT 1 FROM processing_event_outbox o WHERE o.message_id=m.message_id AND o.published_at IS NULL)
            AND NOT EXISTS(SELECT 1 FROM message_processing p WHERE p.message_id=m.message_id AND p.is_current AND p.status IN ('LOADED','PROCESSING'))
            """;
    private String eligibility() {
        if(!"ARCHIVE".equalsIgnoreCase(errorMode)||errorDays<1)return ELIGIBLE;
        return ELIGIBLE.replace("m.status IN ('SENT','PROCESSED')", "(m.status IN ('SENT','PROCESSED') OR (m.status IN ('FAILED','PROCESSING_FAILED') AND coalesce(m.processed_at,m.created_at)<now()-interval '"+errorDays+" days'))")
                .replace("j.status IN ('PENDING','PROCESSING','FAILED')","(j.status IN ('PENDING','PROCESSING') OR (j.status='FAILED' AND m.status NOT IN ('FAILED','PROCESSING_FAILED')))");
    }
    public void enqueue() {
        if(mode.equals("KEEP")) return;
        if(mode.equals("DELETE")) {
            transactions.executeWithoutResult(status -> {
                var ids=jdbc.queryForList("SELECT m.message_id FROM message m WHERE "+ELIGIBLE+" AND m.processed_at<now()-(? * interval '1 day') AND NOT EXISTS(SELECT 1 FROM message_metadata md WHERE md.key='sourceMessageId' AND md.value=m.message_id::text) ORDER BY m.message_id LIMIT 100 FOR UPDATE OF m SKIP LOCKED",Long.class,hotDays);
                for(Long id:ids) { jdbc.update("DELETE FROM rule_execution_job WHERE message_id=?",id);jdbc.update("DELETE FROM message_metadata WHERE message_id=?",id);jdbc.update("DELETE FROM message WHERE message_id=?",id); }
            });
            return;
        }
        jdbc.update("INSERT INTO message_archive_job(message_id,object_key) SELECT m.message_id,? || '/' || m.message_id || '.json' FROM message m WHERE "+eligibility()+" AND (coalesce(m.processed_at,m.created_at)<now()-(? * interval '1 day') OR m.status IN ('FAILED','PROCESSING_FAILED')) AND m.search_version=? ORDER BY m.message_id LIMIT 100 ON CONFLICT DO NOTHING",
                "messages/"+jdbc.queryForObject("SELECT installation_id::text FROM m3_installation WHERE singleton",String.class),hotDays,masking.version());
    }
    public boolean archiveNext() {
        if(!mode.equals("ARCHIVE")) return false;
        Claim claim=transactions.execute(status -> {
            var ids=jdbc.queryForList("SELECT a.message_id FROM message_archive_job a JOIN message m ON m.message_id=a.message_id WHERE a.next_attempt_at<=now() AND (a.claimed_at IS NULL OR a.claimed_at<now()-interval '10 minutes') AND "+eligibility()+" ORDER BY a.next_attempt_at LIMIT 1 FOR UPDATE OF a SKIP LOCKED",Long.class);
            if(ids.isEmpty()) return null;
            long id=ids.getFirst();UUID token=UUID.randomUUID();
            jdbc.update("UPDATE message_archive_job SET claim_token=?,claimed_at=now(),attempts=attempts+1 WHERE message_id=?",token,id);
            String key=jdbc.queryForObject("SELECT object_key FROM message_archive_job WHERE message_id=?",String.class,id);
            byte[] payload=jdbc.queryForObject("SELECT payload_bytes FROM message WHERE message_id=?",byte[].class,id);
            var snapshots=jdbc.query("SELECT job_id,payload_bytes,payload_type,charset,metadata,sha256,stored_size FROM message_delivery_snapshot WHERE message_id=? ORDER BY job_id",(rs,row)->new Snapshot(rs.getLong(1),rs.getBytes(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7)),id);
            var envelope=new Envelope(1,id,payload,snapshots);
            try { return new Claim(id,token,key,json.writeValueAsBytes(envelope),envelope); }
            catch(Exception error) { throw new IllegalStateException("Cannot serialize archive envelope",error); }
        });
        if(claim==null) return false;
        try {
            store.put(claim.key(),claim.bytes());
            String digest=SourceDeliveryService.digest(claim.bytes());
            if(!digest.equals(SourceDeliveryService.digest(store.get(claim.key())))) throw new IllegalStateException("Archive checksum mismatch");
            transactions.executeWithoutResult(status -> {
                // Same lock order as retry: jobs, message, archive job. Upload never holds DB locks.
                jdbc.query("SELECT job_id FROM rule_execution_job WHERE message_id=? ORDER BY job_id FOR UPDATE",rs -> {},claim.id());
                var eligible=jdbc.queryForList("SELECT m.message_id FROM message m WHERE m.message_id=? AND "+eligibility()+" FOR UPDATE OF m",Long.class,claim.id());
                var tokens=jdbc.queryForList("SELECT claim_token FROM message_archive_job WHERE message_id=? FOR UPDATE",UUID.class,claim.id());
                if(eligible.isEmpty() || tokens.isEmpty() || !claim.token().equals(tokens.getFirst())) return;
                byte[] current=jdbc.queryForObject("SELECT payload_bytes FROM message WHERE message_id=?",byte[].class,claim.id());
                if(!Arrays.equals(current,claim.envelope().payload())) throw new IllegalStateException("Payload changed during archive");
                jdbc.update("UPDATE message SET archive_key=?,archive_sha256=?,stored_size=octet_length(payload_bytes),payload_bytes=NULL,payload=NULL,search_text=NULL WHERE message_id=?",claim.key(),digest,claim.id());
                jdbc.update("UPDATE message_delivery_snapshot SET payload_bytes=NULL,metadata='{}' WHERE message_id=?",claim.id());
                jdbc.update("UPDATE rule_execution_job SET result=NULL WHERE message_id=? AND status='COMPLETED'",claim.id());
                jdbc.update("INSERT INTO message_event(message_id,kind,status) VALUES(?,'ARCHIVED','COLD')",claim.id());
                jdbc.update("DELETE FROM message_archive_job WHERE message_id=?",claim.id());
            });
        } catch(Exception failure) {
            jdbc.update("UPDATE message_archive_job SET claimed_at=NULL,claim_token=NULL,next_attempt_at=now()+interval '5 minutes',last_error=? WHERE message_id=? AND claim_token=?",failure.getClass().getSimpleName(),claim.id(),claim.token());
        }
        return true;
    }
    public Envelope read(Message message) {
        if(!message.isArchived()) throw new IllegalArgumentException("Message is not archived");
        try {
            byte[] bytes=store.get(message.getArchiveKey());
            if(!SourceDeliveryService.digest(bytes).equals(message.getArchiveSha256())) throw new IllegalStateException("Archive checksum mismatch");
            Envelope envelope=json.readValue(bytes,Envelope.class);
            if(envelope.version()!=1||envelope.messageId()!=message.getId()||envelope.payload()==null||envelope.payload().length!=message.getPayloadSize())
                throw new IllegalStateException("Archive identity or size mismatch");
            return envelope;
        } catch(Exception error) { throw new IllegalStateException("Archived payload is unavailable or failed integrity verification",error); }
    }
    public void hydrate(Message message) { if(message.isArchived()) message.loadArchivedPayload(read(message).payload()); }
}
