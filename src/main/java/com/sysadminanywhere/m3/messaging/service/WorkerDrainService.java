package com.sysadminanywhere.m3.messaging.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerDrainService {
    private final JdbcTemplate jdbc;
    public WorkerDrainService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional public boolean request(String worker) {
        jdbc.query("SELECT pg_advisory_xact_lock(19732,1)",rs -> {});
        jdbc.update("INSERT INTO worker_drain(worker_name) VALUES(?) ON CONFLICT DO NOTHING",worker);
        return jdbc.queryForObject("SELECT count(*) FROM worker_slot WHERE worker_name=? AND expires_at>now()",Long.class,worker)==0;
    }
    public void resume(String worker) { jdbc.update("DELETE FROM worker_drain WHERE worker_name=?",worker); }
}
