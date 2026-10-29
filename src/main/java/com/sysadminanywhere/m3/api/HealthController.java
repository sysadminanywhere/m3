package com.sysadminanywhere.m3.api;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/health")
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping("/live") public Map<String, String> live() { return Map.of("status", "UP", "service", "community"); }
    @GetMapping("/ready") public ResponseEntity<Map<String, String>> ready() {
        try { jdbc.queryForObject("select 1", Integer.class); return ResponseEntity.ok(Map.of("status", "UP", "database", "UP")); }
        catch (Exception e) { return ResponseEntity.status(503).body(Map.of("status", "DOWN", "database", "DOWN")); }
    }
}
