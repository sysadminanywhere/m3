package com.sysadminanywhere.m3.messaging.service;

import com.sysadminanywhere.m3.messaging.domain.Message;
import com.sysadminanywhere.m3.messaging.domain.MessageDirection;
import com.sysadminanywhere.m3.messaging.domain.MessageStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shareable filters; the final calendar day is included using an exclusive next-day bound. */
public record MessageSearch(Long id, MessageStatus status, String source, String target,
                            LocalDate from, LocalDate to, String text, String correlation) {
    public MessageSearch(Long id,MessageStatus status,String source,String target,LocalDate from,LocalDate to) {
        this(id,status,source,target,from,to,"","");
    }
    public MessageSearch {
        text=text==null?"":text.trim(); correlation=correlation==null?"":correlation.trim();
        if(text.length()>200 || correlation.length()>200) throw new IllegalArgumentException("Search text cannot exceed 200 characters");
        source = source == null ? "" : source.trim();
        target = target == null ? "" : target.trim();
        if (id != null && id <= 0) throw new IllegalArgumentException("Message ID must be a positive integer");
        if (source.length() > 200 || target.length() > 200) throw new IllegalArgumentException("Search text cannot exceed 200 characters");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Start date must not be after end date");
        if ((from != null && (from.getYear() < 1 || from.getYear() > 9999))
                || (to != null && (to.getYear() < 1 || to.getYear() > 9999))) throw new IllegalArgumentException("Invalid date filter");
    }

    public static MessageSearch empty() { return new MessageSearch(null, null, "", "", null, null); }

    public static MessageSearch fromParameters(Map<String, List<String>> params) {
        String id = first(params, "id"), status = first(params, "status");
        Long messageId = null;
        if (!id.isBlank()) {
            try { messageId = Long.parseLong(id); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Message ID must be a positive integer"); }
        }
        MessageStatus state = null;
        if (!status.isBlank()) {
            try { state = MessageStatus.valueOf(status); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid status filter"); }
        }
        return new MessageSearch(messageId, state, first(params, "source"), first(params, "target"),
                date(first(params, "from")), date(first(params, "to")),first(params,"text"),first(params,"correlation"));
    }

    private static String first(Map<String, List<String>> params, String key) {
        return params.getOrDefault(key, List.of()).stream().findFirst().orElse("").trim();
    }
    private static LocalDate date(String value) {
        if (value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid date filter"); }
    }

    public Map<String, String> parameters() {
        var params = new LinkedHashMap<String, String>();
        if (id != null) params.put("id", id.toString());
        if (status != null) params.put("status", status.name());
        if (!source.isEmpty()) params.put("source", source);
        if (!target.isEmpty()) params.put("target", target);
        if (!text.isEmpty()) params.put("text",text);
        if (!correlation.isEmpty()) params.put("correlation",correlation);
        if (from != null) params.put("from", from.toString());
        if (to != null) params.put("to", to.toString());
        return params;
    }

    public Specification<Message> specification(MessageDirection direction, ZoneId zone) {
        return (root, query, cb) -> {
            var conditions = new ArrayList<Predicate>();
            conditions.add(cb.equal(root.get("direction"), direction));
            if (id != null) conditions.add(cb.equal(root.get("id"), id));
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (!source.isEmpty()) conditions.add(cb.like(cb.lower(root.get("sourceSystem")), pattern(source), '\\'));
            if (!target.isEmpty()) conditions.add(cb.like(cb.lower(root.get("targetSystem")), pattern(target), '\\'));
            if (!text.isEmpty()) conditions.add(cb.like(cb.lower(root.get("searchText")),pattern(text),'\\'));
            if (!correlation.isEmpty()) conditions.add(cb.equal(root.get("correlationId"),correlation));
            if (from != null) conditions.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(zone).toInstant()));
            if (to != null) conditions.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(zone).toInstant()));
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    static String pattern(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
