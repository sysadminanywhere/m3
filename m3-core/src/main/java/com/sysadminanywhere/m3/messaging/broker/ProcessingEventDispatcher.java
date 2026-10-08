package com.sysadminanywhere.m3.messaging.broker;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.UUID;
@Service
public class ProcessingEventDispatcher {
    private final JdbcTemplate jdbc;private final ProcessingEventPublisher publisher;
    public ProcessingEventDispatcher(JdbcTemplate jdbc,ProcessingEventPublisher publisher){this.jdbc=jdbc;this.publisher=publisher;}
    record Event(UUID id,String type,String body){}
    @Scheduled(fixedDelayString="${m3.processing.events-delay-ms:1000}") @Transactional
    public void dispatch(){
        var events=jdbc.query("SELECT event_id,event_type,body FROM processing_event_outbox WHERE published_at IS NULL AND next_attempt_at<=now() ORDER BY created_at LIMIT 25 FOR UPDATE SKIP LOCKED",(rs,row)->new Event(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)));
        for(var event:events) {
            try {publisher.publish(event.id(),event.type(),event.body());jdbc.update("UPDATE processing_event_outbox SET published_at=now(),attempts=attempts+1,last_error=NULL WHERE event_id=?",event.id());}
            catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();jdbc.update("UPDATE processing_event_outbox SET attempts=attempts+1,last_error=?,next_attempt_at=now()+interval '10 seconds' WHERE event_id=?",failure.getClass().getSimpleName(),event.id());break;}
        }
    }
}
