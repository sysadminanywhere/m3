package com.sysadminanywhere.m3.messaging.worker;

import com.sysadminanywhere.m3.messaging.service.*;
import com.sysadminanywhere.m3.extensions.ExecutionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** An idle process takes no slot. Active work drains before capacity is reduced. */
@Service @Profile("worker")
public class WorkerSlotService {
    private final JdbcTemplate jdbc;
    private final ExecutionPolicy policy;
    private final WorkerCapacityAllocation allocation;
    private volatile UUID instance=UUID.randomUUID();
    private final String pool,worker;
    private volatile Thread executingThread;
    private volatile boolean lost;
    public WorkerSlotService(JdbcTemplate jdbc,ExecutionPolicy policy,WorkerCapacityAllocation allocation,
            @Value("${m3.worker.pool:default}") String pool,@Value("${HOSTNAME:local-worker}") String worker) {
        this.jdbc=jdbc; this.policy=policy; this.allocation=allocation; this.pool=pool;
        this.worker=worker.substring(0,Math.min(160,worker.length()));
    }
    @Transactional
    public synchronized boolean acquire() {
        jdbc.query("SELECT pg_advisory_xact_lock(19732,1)",rs -> {});
        jdbc.update("DELETE FROM worker_slot WHERE expires_at<=now()");
        if(jdbc.queryForObject("SELECT count(*) FROM worker_drain WHERE worker_name=?",Long.class,worker)>0) return false;
        long global=jdbc.queryForObject("SELECT count(*) FROM worker_slot",Long.class);
        long inPool=jdbc.queryForObject("SELECT count(*) FROM worker_slot WHERE pool_name=?",Long.class,pool);
        if(global>=policy.state().maxWorkers() || inPool>=allocation.targets().getOrDefault(pool,0)) return false;
        instance=UUID.randomUUID();
        jdbc.update("INSERT INTO worker_slot(instance_id,pool_name,worker_name,expires_at,active) VALUES(?,?,?,now()+interval '90 seconds',true)",instance,pool,worker);
        lost=false;
        return true;
    }
    @Transactional
    public synchronized void release() { executingThread=null;jdbc.update("DELETE FROM worker_slot WHERE instance_id=?",instance); }
    public synchronized void begin(){executingThread=Thread.currentThread();}
    public void assertOwned() {
        UUID expected=instance;
        try {
            if(lost || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM worker_slot WHERE instance_id=? AND expires_at>clock_timestamp())",Boolean.class,expected))) throw new IllegalStateException("Worker execution lease was lost");
        } catch(RuntimeException failure) { lose(expected);throw failure; }
    }
    public String token(){return instance.toString();}
    private synchronized void lose(UUID expected){if(!instance.equals(expected))return;lost=true;var thread=executingThread;if(thread!=null)thread.interrupt();}
    @Scheduled(fixedDelay=10000,scheduler="workerLeaseScheduler")
    public void renew() {
        if(executingThread==null)return;
        UUID expected=instance;
        try {if(jdbc.update("UPDATE worker_slot SET expires_at=clock_timestamp()+interval '90 seconds' WHERE instance_id=? AND expires_at>clock_timestamp()",expected)==0)lose(expected);}
        catch(RuntimeException failure){lose(expected);}
    }
}
