package com.sysadminanywhere.m3.base.persistence;
import java.lang.annotation.*;
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditChange { }
