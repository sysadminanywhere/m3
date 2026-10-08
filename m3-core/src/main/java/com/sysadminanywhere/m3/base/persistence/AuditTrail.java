package com.sysadminanywhere.m3.base.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Aspect @Component @Order(100)
@EnableTransactionManagement(order=0)
public class AuditTrail {
    private final JdbcTemplate jdbc;
    public AuditTrail(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Around("@annotation(com.sysadminanywhere.m3.base.persistence.AuditChange)")
    public Object changed(ProceedingJoinPoint invocation) throws Throwable {
        Object result=invocation.proceed();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        String actor=auth==null ? "system" : auth.getName();
        String entity=invocation.getTarget().getClass().getSimpleName();
        Long id=invocation.getArgs().length>0 && invocation.getArgs()[0] instanceof Long value ? value : null;
        if (result != null) {
            try { Object value=result.getClass().getMethod("getId").invoke(result); if(value instanceof Long number) id=number; }
            catch (ReflectiveOperationException ignored) { }
        }
        // No argument values, bodies, credentials or before/after secret snapshots enter the audit table.
        jdbc.update("INSERT INTO configuration_audit(actor,entity_type,entity_id,operation) VALUES(?,?,?,?)",actor,entity,id,invocation.getSignature().getName());
        return result;
    }
}
