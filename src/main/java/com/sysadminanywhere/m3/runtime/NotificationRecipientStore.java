package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Internal persistence boundary for versioned notification recipients. */
@Component
public class NotificationRecipientStore {
    private static final Set<String> EVENTS=Set.of("RETRY","UNCERTAIN","STORAGE_FULL","QUEUE_HIGH");
    private final JdbcTemplate jdbc;
    public NotificationRecipientStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Settings(String name,String channel,boolean enabled,List<String> events,long queueThreshold,int minimumIntervalSeconds,JsonNode destination) {}
    public record Recipient(UUID id,long revision,Settings settings) {}
    public List<Recipient> list() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        return jdbc.query("select id,revision,name,channel,enabled,events,queue_threshold,minimum_interval_seconds,settings::text from community.notification_recipient where deleted_at is null order by name,id",
                (rs,row) -> {
                    try {
                        var events=List.of((String[])rs.getArray(6).getArray());
                        return new Recipient(rs.getObject(1,UUID.class),rs.getLong(2),new Settings(rs.getString(3),rs.getString(4),rs.getBoolean(5),events,rs.getLong(7),rs.getInt(8),mapper.readTree(rs.getString(9))));
                    } catch (java.io.IOException failure) { throw new IllegalStateException("Stored notification settings are invalid",failure); }
                });
    }
    public UUID create(Settings settings) {
        validate(settings); UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into community.notification_recipient(id,name,channel,enabled,events,queue_threshold,minimum_interval_seconds,settings)
                values (?,?,?,?,string_to_array(?,','),?,?,cast(? as jsonb))
                """,id,settings.name(),settings.channel(),settings.enabled(),String.join(",",settings.events()),settings.queueThreshold(),settings.minimumIntervalSeconds(),settings.destination().toString());
        return id;
    }
    public boolean update(UUID id,long expectedRevision,Settings settings) {
        validate(settings);
        if (id==null || expectedRevision<1) throw new IllegalArgumentException("Invalid recipient revision");
        return jdbc.update("""
                update community.notification_recipient set name=?,enabled=?,events=string_to_array(?,','),queue_threshold=?,
                    minimum_interval_seconds=?,settings=cast(? as jsonb),revision=revision+1,updated_at=now()
                where id=? and revision=? and channel=? and deleted_at is null
                """,settings.name(),settings.enabled(),String.join(",",settings.events()),settings.queueThreshold(),settings.minimumIntervalSeconds(),
                settings.destination().toString(),id,expectedRevision,settings.channel())==1;
    }
    public boolean delete(UUID id,long expectedRevision) {
        if (id==null || expectedRevision<1) throw new IllegalArgumentException("Invalid recipient revision");
        return jdbc.update("update community.notification_recipient set enabled=false,deleted_at=now(),revision=revision+1,updated_at=now() where id=? and revision=? and deleted_at is null",id,expectedRevision)==1;
    }
    public UUID requestTest(UUID id) {
        UUID event=UUID.randomUUID();
        int changed=jdbc.update("insert into community.notification_recipient_test_delivery(recipient_id,event_id,state) select id,?,'READY' from community.notification_recipient where id=? and enabled and deleted_at is null",event,id);
        return changed==1?event:null;
    }
    private static void validate(Settings value) {
        if (value==null || value.name()==null || value.name().isBlank() || value.name().length()>120 || !value.name().equals(value.name().trim()) ||
                value.events()==null || value.events().isEmpty() || value.events().stream().anyMatch(event -> event==null || !EVENTS.contains(event)) || Set.copyOf(value.events()).size()!=value.events().size() ||
                value.queueThreshold()<0 || value.minimumIntervalSeconds()<1 || value.minimumIntervalSeconds()>3600)
            throw new IllegalArgumentException("Invalid notification recipient");
        NotificationRecipientSettings.validate(value.channel(),value.destination());
    }
}
