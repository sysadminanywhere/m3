package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.outbound.PreparedOutboundDelivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class MessageHistoryService {
    public record Event(long id,Instant occurredAt,String kind,Long jobId,Long ruleId,String pool,String worker,int attempt,String status,String detail,String actor,Long relatedMessageId,String configurationHash,UUID processingAttemptId,String recipient) { }
    public record Link(long id,String direction,String status) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PayloadMasking masking;
    @org.springframework.beans.factory.annotation.Autowired private OriginalPayloadAccess originalAccess;
    public MessageHistoryService(JdbcTemplate jdbc,ObjectMapper json,PayloadMasking masking) { this.jdbc=jdbc;this.json=json;this.masking=masking; }
    public List<Event> events(long id) {
        return events(id,false);
    }
    public List<Event> events(long id,boolean original) {
        if(original) originalAccess.authorize(id);
        return jdbc.query("SELECT * FROM message_event WHERE message_id=? ORDER BY occurred_at,event_id",(rs,row)->new Event(rs.getLong("event_id"),rs.getTimestamp("occurred_at").toInstant(),rs.getString("kind"),
                rs.getObject("job_id",Long.class),rs.getObject("rule_id",Long.class),rs.getString("pool"),rs.getString("worker"),rs.getInt("attempt"),rs.getString("status"),
                original?rs.getString("detail"):masking.diagnostic(rs.getString("detail")),rs.getString("actor"),rs.getObject("related_message_id",Long.class),rs.getString("configuration_hash"),rs.getObject("processing_attempt_id",UUID.class),rs.getString("recipient")),id);
    }
    public List<Link> links(long id) {
        return jdbc.query("SELECT m.message_id,m.direction,m.status FROM message m WHERE m.message_id IN (SELECT CASE WHEN value ~ '^[0-9]{1,18}$' THEN value::bigint END FROM message_metadata WHERE message_id=? AND key='sourceMessageId') OR EXISTS(SELECT 1 FROM message_metadata md WHERE md.message_id=m.message_id AND md.key='sourceMessageId' AND md.value=?::text) ORDER BY m.message_id",(rs,row)->new Link(rs.getLong(1),rs.getString(2),rs.getString(3)),id,id);
    }
    public void action(long id,String kind,Long related) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        jdbc.update("INSERT INTO message_event(message_id,kind,actor,related_message_id) VALUES(?,?,?,?)",id,kind,auth==null?"system":auth.getName(),related);
    }
    public void prepared(long messageId,long jobId,PreparedOutboundDelivery delivery) {
        try {
            jdbc.update("INSERT INTO message_delivery_snapshot(job_id,message_id,payload_bytes,payload_type,charset,metadata,sha256,stored_size) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(job_id) DO NOTHING",
                    jobId,messageId,delivery.body(),delivery.payloadType(),delivery.metadata().get("charset"),json.writeValueAsString(delivery.metadata()),
                    SourceDeliveryService.digest(delivery.body()),delivery.body().length);
        } catch(Exception error) { throw new IllegalStateException("Cannot persist delivery snapshot",error); }
    }
}
