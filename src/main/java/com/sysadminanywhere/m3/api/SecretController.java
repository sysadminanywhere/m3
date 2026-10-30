package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.security.SecretService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/secrets")
public class SecretController {
    private final SecretService secrets;
    public SecretController(SecretService secrets) { this.secrets = secrets; }
    @PutMapping("/{id}") public ResponseEntity<?> replace(@PathVariable String id, @RequestBody SecretRequest request) {
        if (id.isBlank() || request == null || request.value() == null || request.value().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "secret_id_and_value_required"));
        }
        secrets.replace(id, request.value());
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/{id}") public Map<String, Object> metadata(@PathVariable String id) {
        SecretService.SecretMetadata value = secrets.metadata(id);
        return Map.of("id", value.id(), "updatedAt", value.updatedAt(), "valueAvailable", true);
    }
    public record SecretRequest(String value) {}
}
