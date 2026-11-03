package com.sysadminanywhere.m3.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps diagnostic metadata bounded; no message content or credentials are stored. */
@Component
public class NotificationAttemptRetention {
    private final JdbcTemplate jdbc;
    public NotificationAttemptRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${m3.notification-history.cleanup-ms:3600000}")
    public void cleanup() {
        jdbc.update("delete from community.notification_attempt where id in (select id from community.notification_attempt where finished_at < now()-interval '30 days' order by finished_at limit 10000)");
        jdbc.update("delete from community.notification_recipient_test_delivery where (recipient_id,event_id) in (select recipient_id,event_id from community.notification_recipient_test_delivery where state='DELIVERED' and delivered_at < now()-interval '30 days' order by delivered_at limit 10000)");
    }
}
