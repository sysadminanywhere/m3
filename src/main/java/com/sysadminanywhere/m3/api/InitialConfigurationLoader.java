package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class InitialConfigurationLoader {
    @Bean
    @ConditionalOnProperty(name="m3.bootstrap.enabled", havingValue="true")
    ApplicationRunner initialConfiguration(ConfigurationController configuration, ObjectMapper mapper,
                                           @Value("${m3.bootstrap.file:}") String file) {
        return args -> {
            if (file.isBlank()) throw new IllegalStateException("m3.bootstrap.file is required when bootstrap is enabled");
            if (configuration.version().get("version") != 0) return;
            Object parsed;
            try (var reader = Files.newBufferedReader(Path.of(file))) { parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader); }
            var document = mapper.valueToTree(parsed);
            var result = configuration.publish(new ConfigurationController.PublishRequest(document, 0));
            if (result.getStatusCode().value() == 409) return; // Another replica published first.
            if (!result.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("Initial configuration is invalid; inspect it with the validation API");
        };
    }
}
