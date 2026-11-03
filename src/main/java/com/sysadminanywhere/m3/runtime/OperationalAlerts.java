package com.sysadminanywhere.m3.runtime;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Records only transitions, so repeated polls do not flood notification recipients. */
@Component
public class OperationalAlerts {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public OperationalAlerts(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager); }
    public void set(String key,String type,boolean active) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into community.operational_alert_state(alert_key,active) values (?,false) on conflict do nothing",key);
            int changed=jdbc.update("update community.operational_alert_state set active=?,updated_at=now() where alert_key=? and active is distinct from ?",active,key,active);
            if (changed==1 && active) jdbc.update("insert into community.operational_alert_event(id,alert_key,event_type) values (?,?,?)",UUID.randomUUID(),key,type);
        });
    }
}
