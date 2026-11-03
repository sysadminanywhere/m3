package com.sysadminanywhere.m3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Set;

/** Validates only non-secret recipient settings before they can be persisted. */
public final class NotificationRecipientSettings {
    private NotificationRecipientSettings() {}
    public static void validate(String channel,JsonNode settings) {
        if (settings==null || !settings.isObject()) throw invalid();
        if ("webhook".equals(channel)) {
            only(settings,Set.of("url","tokenRef"));
            String value=required(settings,"url",2048);
            URI uri;
            try { uri=URI.create(value); } catch (IllegalArgumentException failure) { throw invalid(); }
            String host=uri.getHost();
            boolean local=host!=null && Set.of("localhost","127.0.0.1","::1","[::1]").contains(host.toLowerCase(java.util.Locale.ROOT));
            if (host==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null ||
                    !("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))) throw invalid();
            optional(settings,"tokenRef",200);
        } else if ("email".equals(channel)) {
            only(settings,Set.of("host","port","from","to","username","passwordRef","allowPlaintextLoopback"));
            String host=required(settings,"host",253); String from=required(settings,"from",320); String to=required(settings,"to",320);
            if (!host.matches("[A-Za-z0-9.:-]+") || !settings.path("port").isIntegralNumber() || !settings.path("port").canConvertToInt() || settings.path("port").asInt()<1 || settings.path("port").asInt()>65535) throw invalid();
            String username=optional(settings,"username",320), ref=optional(settings,"passwordRef",200);
            if (username.isBlank()!=ref.isBlank()) throw invalid();
            if (settings.has("allowPlaintextLoopback") && !settings.path("allowPlaintextLoopback").isBoolean()) throw invalid();
            if (settings.path("allowPlaintextLoopback").asBoolean(false) && !Set.of("localhost","127.0.0.1","::1").contains(host.toLowerCase(java.util.Locale.ROOT))) throw invalid();
            try { new InternetAddress(from,true).validate(); new InternetAddress(to,true).validate(); }
            catch (jakarta.mail.internet.AddressException failure) { throw invalid(); }
        } else throw invalid();
    }
    private static void only(JsonNode node,Set<String> allowed) { node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid(); }); }
    private static String required(JsonNode node,String field,int max) { String value=optional(node,field,max); if (value.isBlank()) throw invalid(); return value; }
    private static String optional(JsonNode node,String field,int max) {
        JsonNode value=node.get(field); if (value==null || value.isNull()) return "";
        if (!value.isTextual() || value.asText().length()>max || !value.asText().equals(value.asText().trim()) || value.asText().chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.asText();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid notification recipient settings"); }
}
