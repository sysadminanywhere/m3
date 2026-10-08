package com.sysadminanywhere.m3.messaging.worker;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Wrap the entire operation, including outbound network I/O, before its transaction starts. */
@Aspect @Component @Profile("worker") @Order(-100)
public class LicensedWorkerExecution {
    private final WorkerSlotService slots;
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    public LicensedWorkerExecution(WorkerSlotService slots) { this.slots=slots; }
    @Around("execution(* com.sysadminanywhere.m3.messaging.service.RuleExecutionProcessor.processNext(..)) || execution(* com.sysadminanywhere.m3.messaging.outbound.OutboundDeliveryWorker.processNext(..))")
    public Object execute(ProceedingJoinPoint invocation) throws Throwable {
        if (!busy.compareAndSet(false,true)) return false;
        boolean acquired=false;
        try { acquired=slots.acquire();if(!acquired)return false;slots.begin();ExecutionLease.enter(slots);slots.assertOwned();return invocation.proceed(); }
        finally { ExecutionLease.leave();try { if(acquired) slots.release(); } finally { busy.set(false); } }
    }
}
