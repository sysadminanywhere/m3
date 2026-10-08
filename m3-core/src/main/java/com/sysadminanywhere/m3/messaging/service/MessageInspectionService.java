package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MessageInspectionService {
    @org.springframework.beans.factory.annotation.Autowired private com.sysadminanywhere.m3.base.security.MessageAccess messageAccess;
    public record PayloadView(byte[] bytes,String text,String type,String charset,Map<String,String> metadata,boolean original) { }
    public record BodyVersion(Long jobId,String label) { }
    private final MessageRepository messages;
    private final MessageArchiveService archive;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PayloadMasking masking;
    private final OriginalPayloadAccess access;
    public MessageInspectionService(MessageRepository messages,MessageArchiveService archive,org.springframework.jdbc.core.JdbcTemplate jdbc,ObjectMapper json,PayloadMasking masking,OriginalPayloadAccess access) {
        this.messages=messages;this.archive=archive;this.jdbc=jdbc;this.json=json;this.masking=masking;this.access=access;
    }
    @Transactional
    public PayloadView view(Long id,Long jobId,boolean original,String charsetOverride) {
        if(messageAccess!=null) messageAccess.require(id,original?"original":"read",null);
        if(original) access.authorize(id);
        var message=messages.findById(id).orElseThrow(() -> new IllegalArgumentException("Message not found"));
        byte[] bytes;String type,charset;Map<String,String> metadata=new TreeMap<>();
        if(jobId==null) {
            archive.hydrate(message); bytes=message.getPayloadBytes();type=message.getPayloadType();charset=message.getCharset();
            message.getMetadata().forEach(item -> metadata.put(item.getKey(),item.getValue()));
        } else {
            MessageArchiveService.Snapshot snapshot;
            if(message.isArchived()) snapshot=archive.read(message).deliveries().stream().filter(s -> s.jobId()==jobId).findFirst().orElseThrow(() -> new IllegalArgumentException("Delivery snapshot not found"));
            else snapshot=jdbc.query("SELECT job_id,payload_bytes,payload_type,charset,metadata,sha256,stored_size FROM message_delivery_snapshot WHERE message_id=? AND job_id=?",(rs,row)->new MessageArchiveService.Snapshot(rs.getLong(1),rs.getBytes(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7)),id,jobId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Delivery snapshot not found"));
            bytes=snapshot.bytes();type=snapshot.type();charset=snapshot.charset();
            try { metadata.putAll(json.readValue(snapshot.metadata(),new TypeReference<Map<String,String>>(){})); }
            catch(Exception invalid) { throw new IllegalStateException("Invalid snapshot metadata",invalid); }
            if(!SourceDeliveryService.digest(bytes).equals(snapshot.sha256())) throw new IllegalStateException("Delivery snapshot checksum mismatch");
        }
        String selected=charsetOverride==null?charset:PayloadCodec.charset(charsetOverride);
        String text;
        if(original) {
            try { text=selected==null?Base64.getEncoder().encodeToString(bytes):PayloadCodec.decode(bytes,selected); }
            catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("Cannot decode with the selected charset"); }
        } else {
            text=masking.payload(bytes,selected,type);
            metadata.replaceAll(masking::metadata);
        }
        return new PayloadView(original?bytes:text.getBytes(StandardCharsets.UTF_8),text,type,original?selected:"UTF-8",Collections.unmodifiableMap(metadata),original);
    }
    public List<BodyVersion> versions(long id) {
        var versions=new ArrayList<BodyVersion>(); versions.add(new BodyVersion(null,"Stored payload"));
        versions.addAll(jdbc.query("SELECT job_id FROM message_delivery_snapshot WHERE message_id=? ORDER BY job_id",(rs,row)->new BodyVersion(rs.getLong(1),"Prepared delivery #"+rs.getLong(1)),id));
        return versions;
    }
    public String safeDiagnostic(String error) { return masking.diagnostic(error); }
    public String safeMetadata(String key,String value) { return masking.metadata(key,value); }
    public String safePayload(String body,String type) { return masking.payload(body,type); }
    public String searchVersion() { return masking.version(); }
}
