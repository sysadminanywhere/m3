package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class TopologyController {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public TopologyController(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    @GetMapping("/topology") public Map<String,Object> topology() throws Exception {
        List<String> bundles=jdbc.query("select document_json::text from community.config_bundle order by version desc limit 1", (rs,row)->rs.getString(1));
        List<String> sources = new ArrayList<>(); var groups = new LinkedHashSet<String>();
        if (!bundles.isEmpty()) {
            JsonNode config=mapper.readTree(bundles.get(0));
            for (JsonNode source:config.path("sources")) sources.add(source.path("id").asText());
            for (JsonNode rule:config.path("rules")) { String group=rule.path("executionGroup").asText("default"); groups.add(group.isBlank()?"default":group); }
        }
        return Map.of("sources",sources,"groups",List.copyOf(groups));
    }
}
