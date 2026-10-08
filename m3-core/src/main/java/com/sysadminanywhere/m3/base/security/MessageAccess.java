package com.sysadminanywhere.m3.base.security;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

@Component
public class MessageAccess {
    private final MachineAccounts accounts; private final JdbcTemplate jdbc;
    public MessageAccess(MachineAccounts accounts,JdbcTemplate jdbc){this.accounts=accounts;this.jdbc=jdbc;}
    public void require(long id,String operation,String recipient) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SERVICE"))) return;
        var account=accounts.getServices().values().stream().filter(a->auth.getName().equals(a.getUsername())).findFirst().orElseThrow(()->new AccessDeniedException("Unknown service"));
        var channels=jdbc.queryForList("SELECT source_channel_id FROM message WHERE message_id=? AND direction='INBOUND'",Long.class,id);
        if(channels.isEmpty() || !account.getChannels().contains(channels.getFirst())) throw new AccessDeniedException("Message is outside service scope");
        if(recipient!=null && !account.getRecipients().contains(recipient)) throw new AccessDeniedException("Recipient is outside service scope");
        if(operation.equals("status")&&!account.isStatus() || operation.equals("replay")&&!account.isReplay() || operation.equals("original")&&!account.isOriginal()) throw new AccessDeniedException("Operation is outside service scope");
    }
    public boolean recipientVisible(String recipient) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_SERVICE"))) return true;
        return accounts.getServices().values().stream().anyMatch(a->auth.getName().equals(a.getUsername())&&a.getRecipients().contains(recipient));
    }
}
