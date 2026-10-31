package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractTest {
    @Test void openApiReferencesResolveAndExampleMatchesValidation() throws Exception {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<?,?> contract;
        try (var reader = Files.newBufferedReader(Path.of("docs/v2/api-contract.yaml"))) { contract = yaml.load(reader); }
        assertEquals("3.0.3", contract.get("openapi"));
        Map<?,?> schemas = (Map<?,?>) ((Map<?,?>) contract.get("components")).get("schemas");
        checkReferences(contract, schemas);

        var mapper = new ObjectMapper(); var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select coalesce(max(version),0) from community.config_bundle", Integer.class)).thenReturn(0);
        var controller = new ConfigurationController(jdbc, mapper);
        var example = mapper.readTree(Files.readString(Path.of("docs/v2/configuration.example.json")));
        assertTrue(controller.validate(example).errors().isEmpty());
        ((com.fasterxml.jackson.databind.node.ObjectNode) example.path("connections").get(0).path("settings")).put("path", " ");
        assertTrue(controller.validate(example).errors().stream().anyMatch(error -> error.contains("settings.path")));
    }

    private static void checkReferences(Object node, Map<?,?> schemas) {
        if (node instanceof Map<?,?> map) {
            Object ref = map.get("$ref");
            if (ref != null) {
                String path = ref.toString();
                assertTrue(path.startsWith("#/components/schemas/"), path);
                assertTrue(schemas.containsKey(path.substring("#/components/schemas/".length())), path);
            }
            map.values().forEach(value -> checkReferences(value, schemas));
        } else if (node instanceof List<?> list) list.forEach(value -> checkReferences(value, schemas));
    }
}
