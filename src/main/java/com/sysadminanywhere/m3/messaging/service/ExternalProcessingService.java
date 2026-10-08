package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.base.security.MessageAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@Service
public class ExternalProcessingService {
    public record Attempt(UUID attemptId,long messageId,String recipient,boolean required,int attemptNo,MessageStatus status,long version,
                          Instant createdAt,Instant startedAt,Instant completedAt,Instant deadlineAt,boolean overdue,String detail,
                          int loadedTimeoutSeconds,int processingTimeoutSeconds) { }
    public record Result(Attempt processing,MessageStatus messageStatus,Instant processedAt) { }
    public record Callback(UUID callbackId,UUID processingAttemptId,Long expectedVersion,MessageStatus status,MessageStatus expectedStatus,String detail) { }
    public record Replay(UUID requestId,UUID processingAttemptId,Long expectedVersion,boolean acknowledgeDuplicate) { }
    public record Recipient(String name,boolean required) { }
    public record History(List<Attempt> attempts,long total) { }
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final PayloadMasking masking; private final MessageAccess access;
    private final int loadedSeconds,processingSeconds;
    public ExternalProcessingService(JdbcTemplate jdbc,ObjectMapper json,PayloadMasking masking,MessageAccess access,
            @Value("${m3.processing.loaded-timeout-seconds:86400}") int loadedSeconds,
            @Value("${m3.processing.processing-timeout-seconds:3600}") int processingSeconds) {
        this.jdbc=jdbc;this.json=json;this.masking=masking;this.access=access;this.loadedSeconds=loadedSeconds;this.processingSeconds=processingSeconds;
        if(loadedSeconds<1 || processingSeconds<1 || loadedSeconds>31536000 || processingSeconds>31536000) throw new IllegalArgumentException("Processing timeouts must be between 1 second and 1 year");
    }
    public static List<Recipient> recipients(Rule rule) {
        var values=rule.getLoadingProperties().getOrDefault("processingRecipients","external").split(",",-1);
        if(values.length>20) throw new IllegalArgumentException("At most 20 processing recipients are supported");
        var result=new ArrayList<Recipient>();var names=new HashSet<String>();
        for(String value:values) {
            value=value.trim();boolean required=!value.endsWith("?");String name=required?value:value.substring(0,value.length()-1);
            if(!name.matches("[a-zA-Z][a-zA-Z0-9_-]{0,79}") || !names.add(name)) throw new IllegalArgumentException("Invalid or duplicate processing recipient");
            result.add(new Recipient(name,required));
        }
        if(result.stream().noneMatch(Recipient::required)) throw new IllegalArgumentException("At least one required recipient is needed");
        return result;
    }
    public static int timeout(Rule rule,String key,int fallback) {
        int value=Integer.parseInt(rule.getLoadingProperties().getOrDefault(key,Integer.toString(fallback)));
        if(value<1 || value>31536000) throw new IllegalArgumentException("Processing timeout must be between 1 second and 1 year");return value;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void initialize(Message message,Rule rule) {
        for(var recipient:recipients(rule)) {
            var attempt=insert(message.getId(),recipient.name(),recipient.required(),1,rule.getInitialMessageStatus(),
                    timeout(rule,"loadedTimeoutSeconds",loadedSeconds),timeout(rule,"processingTimeoutSeconds",processingSeconds));
            event(attempt,switch(attempt.status()) {
                case PROCESSING -> "processing.started";
                case PROCESSED -> "processing.completed";
                case PROCESSING_FAILED -> "processing.failed";
                default -> "processing.requested";
            });
        }
    }
    private Attempt insert(long id,String recipient,boolean required,int number,MessageStatus status,int loaded,int processing) {
        UUID attempt=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO message_processing(attempt_id,message_id,recipient,required,attempt_no,status,started_at,completed_at,deadline_at,loaded_timeout_seconds,processing_timeout_seconds)
                VALUES(?,?,?,?,?,?,CASE WHEN ?='PROCESSING' THEN now() END,CASE WHEN ? IN ('PROCESSED','PROCESSING_FAILED') THEN now() END,
                    CASE WHEN ?='LOADED' THEN now()+(?*interval '1 second') WHEN ?='PROCESSING' THEN now()+(?*interval '1 second') END,?,?)
                """,attempt,id,recipient,required,number,status.name(),status.name(),status.name(),status.name(),loaded,status.name(),processing,loaded,processing);
        return current(id,recipient);
    }
    @Transactional(readOnly=true)
    public List<Attempt> attempts(long id,boolean includeHistory) {
        access.require(id,"read",null);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM message WHERE message_id=? AND direction='INBOUND')",Boolean.class,id))) throw error(HttpStatus.NOT_FOUND,"Inbound message not found");
        return jdbc.query("SELECT * FROM message_processing WHERE message_id=?"+(includeHistory?"":" AND is_current")+" ORDER BY recipient,attempt_no",this::map,id)
                .stream().filter(a->access.recipientVisible(a.recipient())).map(this::safe).toList();
    }
    @Transactional(readOnly=true)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public History attemptHistory(long id,int limit){
        if(limit<1||limit>200)throw new IllegalArgumentException("History limit must be between 1 and 200");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM message WHERE message_id=? AND direction='INBOUND')",Boolean.class,id)))throw error(HttpStatus.NOT_FOUND,"Inbound message not found");
        long total=jdbc.queryForObject("SELECT count(*) FROM message_processing WHERE message_id=?",Long.class,id);
        var rows=jdbc.query("SELECT * FROM message_processing WHERE message_id=? ORDER BY created_at DESC,attempt_id LIMIT ?",this::map,id,limit).stream().map(this::safe).toList();
        return new History(rows,total);
    }
    private Attempt map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Attempt(rs.getObject("attempt_id",UUID.class),rs.getLong("message_id"),rs.getString("recipient"),rs.getBoolean("required"),rs.getInt("attempt_no"),
                MessageStatus.valueOf(rs.getString("status")),rs.getLong("version"),instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"completed_at"),
                instant(rs,"deadline_at"),rs.getBoolean("overdue"),rs.getString("detail"),rs.getInt("loaded_timeout_seconds"),rs.getInt("processing_timeout_seconds"));
    }
    private static Instant instant(java.sql.ResultSet rs,String key) throws java.sql.SQLException { var value=rs.getTimestamp(key);return value==null?null:value.toInstant(); }
    private Attempt safe(Attempt a) { return new Attempt(a.attemptId(),a.messageId(),a.recipient(),a.required(),a.attemptNo(),a.status(),a.version(),a.createdAt(),a.startedAt(),a.completedAt(),a.deadlineAt(),a.overdue(),masking.diagnostic(a.detail()),a.loadedTimeoutSeconds(),a.processingTimeoutSeconds()); }
    private Attempt current(long id,String recipient) { return jdbc.query("SELECT * FROM message_processing WHERE message_id=? AND recipient=? AND is_current",this::map,id,recipient).stream().findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"Processing recipient not found")); }
    private void lock(long id) {
        var rows=jdbc.queryForList("SELECT direction FROM message WHERE message_id=? FOR UPDATE",String.class,id);
        if(rows.isEmpty()) throw error(HttpStatus.NOT_FOUND,"Message not found");
        if(!rows.getFirst().equals("INBOUND")) throw error(HttpStatus.CONFLICT,"External processing applies only to inbound messages");
    }
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.processing()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Result callback(Long id,String recipient,Callback request) {
        access.require(id,"status",recipient);
        if(request.callbackId()==null || request.processingAttemptId()==null || request.expectedVersion()==null || request.expectedVersion()<0 || request.status()==null || !request.status().isInboundProcessingStatus() || request.detail()!=null&&request.detail().length()>2000)
            throw error(HttpStatus.BAD_REQUEST,"Callback identity, attempt, version and valid status are required");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,19733))",rs->{},request.callbackId().toString());lock(id);
        String digest=digest(List.of(id,recipient,request));
        var previous=jdbc.queryForList("SELECT request_digest,response FROM processing_callback WHERE callback_id=?",request.callbackId());
        if(!previous.isEmpty()) return cached(previous.getFirst(),digest);
        var a=current(id,recipient);check(a,request.processingAttemptId(),request.expectedVersion());
        if(request.expectedStatus()!=null&&a.status()!=request.expectedStatus()) throw error(HttpStatus.CONFLICT,"Processing status changed");
        if(a.status()!=request.status()) {
            if(a.status().isProcessingComplete() || request.status()==MessageStatus.LOADED) throw error(HttpStatus.CONFLICT,"Start a new attempt to reopen processing");
            jdbc.update("""
                    UPDATE message_processing SET status=?,version=version+1,detail=?,overdue=false,
                      started_at=CASE WHEN ?='PROCESSING' THEN coalesce(started_at,now()) ELSE started_at END,
                      completed_at=CASE WHEN ? IN ('PROCESSED','PROCESSING_FAILED') THEN now() END,
                      deadline_at=CASE WHEN ?='PROCESSING' THEN now()+(processing_timeout_seconds*interval '1 second') END WHERE attempt_id=?
                    """,request.status().name(),request.detail(),request.status().name(),request.status().name(),request.status().name(),a.attemptId());
            a=current(id,recipient);aggregate(id);
            event(a,switch(a.status()){case PROCESSING->"processing.started";case PROCESSED->"processing.completed";case PROCESSING_FAILED->"processing.failed";default->"processing.requested";});
            history(a,"EXTERNAL_STATUS_CHANGED",request.detail());resolve(a);
        }
        var response=result(a);jdbc.update("INSERT INTO processing_callback(callback_id,attempt_id,request_digest,response) VALUES(?,?,?,?)",request.callbackId(),a.attemptId(),digest,write(response));return response;
    }
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.processing()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public Result replay(Long id,String recipient,Replay request) {
        access.require(id,"replay",recipient);
        if(request.requestId()==null || request.processingAttemptId()==null || request.expectedVersion()==null || request.expectedVersion()<0) throw error(HttpStatus.BAD_REQUEST,"Replay identity, attempt and version are required");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,19734))",rs->{},request.requestId().toString());lock(id);
        String digest=digest(List.of(id,recipient,request));var previous=jdbc.queryForList("SELECT request_digest,response FROM processing_replay WHERE request_id=?",request.requestId());
        if(!previous.isEmpty()) return cached(previous.getFirst(),digest);
        var a=current(id,recipient);check(a,request.processingAttemptId(),request.expectedVersion());
        if(a.status()!=MessageStatus.PROCESSING_FAILED && !a.overdue() && !request.acknowledgeDuplicate()) throw error(HttpStatus.CONFLICT,"Acknowledge duplicate business effects before replaying active or successful processing");
        Long pending=jdbc.queryForObject("SELECT count(*) FROM rule_execution_job WHERE message_id=? AND status IN ('PENDING','PROCESSING','FAILED')",Long.class,id);
        if(pending>0) throw error(HttpStatus.CONFLICT,"Resolve routing jobs before external replay");
        jdbc.update("UPDATE message_processing SET is_current=false WHERE attempt_id=?",a.attemptId());resolve(a);
        var next=insert(id,recipient,a.required(),a.attemptNo()+1,MessageStatus.LOADED,a.loadedTimeoutSeconds(),a.processingTimeoutSeconds());aggregate(id);
        event(next,"processing.requested");history(next,"EXTERNAL_REPLAY_REQUESTED",null);
        var response=result(next);jdbc.update("INSERT INTO processing_replay(request_id,message_id,recipient,request_digest,response) VALUES(?,?,?,?,?)",request.requestId(),id,recipient,digest,write(response));return response;
    }
    private void check(Attempt a,UUID attempt,long version) { if(!a.attemptId().equals(attempt)||a.version()!=version) throw error(HttpStatus.CONFLICT,"Processing attempt or version changed"); }
    private void aggregate(long id) {
        var required=jdbc.queryForList("SELECT status FROM message_processing WHERE message_id=? AND is_current AND required",String.class,id);
        MessageStatus state=required.contains("PROCESSING_FAILED")?MessageStatus.PROCESSING_FAILED:
                required.stream().allMatch("PROCESSED"::equals)?MessageStatus.PROCESSED:
                required.stream().anyMatch(s->!s.equals("LOADED"))?MessageStatus.PROCESSING:MessageStatus.LOADED;
        jdbc.update("UPDATE message SET status=?,processed_at=CASE WHEN ? IN ('PROCESSED','PROCESSING_FAILED') THEN (SELECT max(completed_at) FROM message_processing WHERE message_id=? AND is_current AND required) END WHERE message_id=?",state.name(),state.name(),id,id);
    }
    private Result result(Attempt a) {
        return jdbc.query("SELECT status,processed_at FROM message WHERE message_id=?",(rs,row)->new Result(safe(a),MessageStatus.valueOf(rs.getString(1)),rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant()),a.messageId()).getFirst();
    }
    private Result cached(Map<String,Object> row,String digest) {
        if(!digest.equals(row.get("request_digest"))) throw error(HttpStatus.CONFLICT,"Request ID was reused with different data");
        try{return json.readValue((String)row.get("response"),Result.class);}catch(Exception e){throw new IllegalStateException("Invalid saved callback",e);}
    }
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot serialize processing event",e);}}
    private String digest(Object value) {return SourceDeliveryService.digest(write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void event(Attempt a,String type) {
        UUID id=UUID.randomUUID();var body=new LinkedHashMap<String,Object>();body.put("eventId",id);body.put("eventType",type);body.put("schemaVersion",1);
        body.put("messageId",a.messageId());body.put("recipient",a.recipient());body.put("processingAttemptId",a.attemptId());body.put("attemptNo",a.attemptNo());body.put("version",a.version());
        body.put("status",a.status());body.put("deadlineAt",a.deadlineAt());body.put("occurredAt",Instant.now());body.put("resourcePath","/api/v1/messages/"+a.messageId());
        body.put("idempotencyKey","m3:"+a.messageId()+":"+a.recipient()+":"+a.attemptId());
        jdbc.update("INSERT INTO processing_event_outbox(event_id,message_id,attempt_id,event_type,body) VALUES(?,?,?,?,?)",id,a.messageId(),a.attemptId(),type,write(body));
    }
    private void history(Attempt a,String kind,String detail) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        jdbc.update("INSERT INTO message_event(message_id,kind,attempt,status,detail,actor,processing_attempt_id,recipient) VALUES(?,?,?,?,?,?,?,?)",a.messageId(),kind,a.attemptNo(),a.status().name(),detail,auth==null?"system":auth.getName(),a.attemptId(),a.recipient());
    }
    private void resolve(Attempt a) {jdbc.update("UPDATE operational_alert SET resolved_at=now() WHERE alert_key=? AND resolved_at IS NULL","processing:"+a.attemptId());}
    @Transactional
    public void detectOverdue() {
        var ids=jdbc.queryForList("SELECT DISTINCT message_id FROM message_processing WHERE is_current AND NOT overdue AND deadline_at<now() ORDER BY message_id LIMIT 100",Long.class);
        for(Long id:ids) {
            if(jdbc.queryForList("SELECT message_id FROM message WHERE message_id=? FOR UPDATE SKIP LOCKED",Long.class,id).isEmpty()) continue;
            var attempts=jdbc.query("SELECT * FROM message_processing WHERE message_id=? AND is_current AND NOT overdue AND deadline_at<now()",this::map,id);
            for(var a:attempts) {
                jdbc.update("UPDATE message_processing SET overdue=true WHERE attempt_id=?",a.attemptId());event(a,"processing.overdue");history(a,"PROCESSING_OVERDUE",null);
                jdbc.update("INSERT INTO operational_alert(alert_key,severity,detail) VALUES(?,'WARNING',?) ON CONFLICT(alert_key) DO UPDATE SET last_seen=now(),resolved_at=NULL",
                        "processing:"+a.attemptId(),"Message "+id+", recipient "+a.recipient()+" exceeded its processing deadline");
            }
        }
    }
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
