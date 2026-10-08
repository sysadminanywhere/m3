package com.sysadminanywhere.m3.base.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @ConfigurationProperties(prefix="m3.security")
public class MachineAccounts {
    private Map<String,Account> services = new LinkedHashMap<>();
    public Map<String,Account> getServices() { return services; }
    public void setServices(Map<String,Account> value) { services=value; }
    public static class Account {
        private String username,passwordHash;
        private Set<Long> channels = new HashSet<>();
        private Set<String> recipients = new HashSet<>();
        private boolean original,status,replay;
        public String getUsername(){return username;} public void setUsername(String v){username=v;}
        public String getPasswordHash(){return passwordHash;} public void setPasswordHash(String v){passwordHash=v;}
        public Set<Long> getChannels(){return channels;} public void setChannels(Set<Long> v){channels=v;}
        public Set<String> getRecipients(){return recipients;} public void setRecipients(Set<String> v){recipients=v;}
        public boolean isOriginal(){return original;} public void setOriginal(boolean v){original=v;}
        public boolean isStatus(){return status;} public void setStatus(boolean v){status=v;}
        public boolean isReplay(){return replay;} public void setReplay(boolean v){replay=v;}
    }
}
