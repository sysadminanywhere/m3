package com.sysadminanywhere.m3.messaging.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OriginalPayloadAccess {
    @org.springframework.beans.factory.annotation.Autowired private com.sysadminanywhere.m3.base.security.MessageAccess access;
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("@serviceAccess.original()")
    @com.sysadminanywhere.m3.base.persistence.AuditChange
    public void authorize(Long messageId) { access.require(messageId,"original",null); }
}
