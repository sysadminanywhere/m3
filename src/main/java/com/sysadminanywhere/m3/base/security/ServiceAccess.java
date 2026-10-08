package com.sysadminanywhere.m3.base.security;

import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import com.vaadin.flow.component.UI;

/** Background initialization is trusted; authenticated RPC callers must have the required role. */
@Component("serviceAccess")
public class ServiceAccess {
    public boolean configure() { return allowed("ROLE_ADMIN"); }
    public boolean operate() { return allowed("ROLE_ADMIN", "ROLE_OPERATOR"); }
    public boolean processing() { return allowed("ROLE_ADMIN", "ROLE_OPERATOR", "ROLE_SERVICE"); }
    public boolean original() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_PAYLOAD_ORIGINAL"));
    }
    private boolean allowed(String... roles) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return RequestContextHolder.getRequestAttributes() == null && UI.getCurrent() == null;
        return authentication.isAuthenticated() && authentication.getAuthorities().stream()
                .anyMatch(authority -> java.util.List.of(roles).contains(authority.getAuthority()));
    }
}
