package com.sysadminanywhere.m3.messaging.source;

import com.sysadminanywhere.m3.messaging.domain.*;
import java.util.List;

public final class LoadingPolicy {
    private LoadingPolicy() { }
    public static void validate(Rule rule, List<Rule> rules) {
        if (rule.getRuleType() != RuleType.INBOUND || !Boolean.TRUE.equals(rule.getEnabled())) return;
        boolean file = switch (rule.getSourceChannel().getChannelType()) {
            case DIRECTORY, FTP, SFTP -> true;
            default -> false;
        };
        if (!file) return;
        for (Rule other : rules) {
            if (other.getId().equals(rule.getId()) || other.getRuleType() != RuleType.INBOUND
                    || !Boolean.TRUE.equals(other.getEnabled())
                    || !other.getSourceChannel().getId().equals(rule.getSourceChannel().getId())) continue;
            if (deletes(rule) || deletes(other))
                throw new IllegalArgumentException("File deletion requires a single active loading rule for the source channel");
        }
    }
    private static boolean deletes(Rule rule) {
        return Boolean.parseBoolean(rule.getLoadingProperties().getOrDefault("deleteAfterProcessing", "false"))
                || Boolean.parseBoolean(rule.getLoadingProperties().getOrDefault("deleteRemoteFiles", "false"));
    }
}
