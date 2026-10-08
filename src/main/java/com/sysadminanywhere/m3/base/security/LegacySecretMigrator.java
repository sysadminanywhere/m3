package com.sysadminanywhere.m3.base.security;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!worker")
public class LegacySecretMigrator implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final SecretCipher cipher;
    public LegacySecretMigrator(JdbcTemplate jdbc,SecretCipher cipher) { this.jdbc=jdbc; this.cipher=cipher; }
    @Override @Transactional public void run(ApplicationArguments args) {
        while (true) {
            var rows=jdbc.query("SELECT channel_id,property_key,property_value FROM channel_properties WHERE property_value IS NOT NULL AND property_value NOT LIKE 'm3enc:v1:%' LIMIT 500 FOR UPDATE SKIP LOCKED",
                    (rs,n)->new Property(rs.getLong(1),rs.getString(2),rs.getString(3)));
            if (rows.isEmpty()) break;
            for (var row:rows) jdbc.update("UPDATE channel_properties SET property_value=? WHERE channel_id=? AND property_key=?",cipher.encrypt(row.value()),row.channel(),row.key());
        }
        // Detect a wrong key at startup instead of failing unpredictably when a channel is opened.
        jdbc.query("SELECT property_value FROM channel_properties WHERE property_value LIKE 'm3enc:v1:%' LIMIT 1",rs->{cipher.decrypt(rs.getString(1));});
        jdbc.query("SELECT configuration_snapshot FROM rule_execution_job WHERE configuration_snapshot LIKE 'm3enc:v1:%' LIMIT 1",rs->{cipher.decrypt(rs.getString(1));});
    }
    private record Property(long channel,String key,String value) { }
}
