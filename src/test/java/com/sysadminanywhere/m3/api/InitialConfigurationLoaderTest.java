package com.sysadminanywhere.m3.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InitialConfigurationLoaderTest {
    @Test void loadsYamlOnlyForEmptyInstallation(@TempDir Path dir) throws Exception {
        Path file=Files.writeString(dir.resolve("config.yaml"), "formatVersion: '2'\nsources: []\n");
        var configuration=mock(ConfigurationController.class);
        when(configuration.version()).thenReturn(Map.of("version",0),Map.of("version",1));
        doReturn(ResponseEntity.ok(Map.of("version",1))).when(configuration).publish(any());
        var runner=new InitialConfigurationLoader().initialConfiguration(configuration,new ObjectMapper(),file.toString());
        runner.run(null); runner.run(null);
        var request=org.mockito.ArgumentCaptor.forClass(ConfigurationController.PublishRequest.class);
        verify(configuration, times(1)).publish(request.capture());
        assertEquals(0,request.getValue().expectedVersion());
        assertEquals("2",request.getValue().document().path("formatVersion").asText());
    }
    @Test void rejectsMissingPathWhenEnabled() throws Exception {
        var runner=new InitialConfigurationLoader().initialConfiguration(mock(ConfigurationController.class),new ObjectMapper(),"");
        assertThrows(IllegalStateException.class,() -> runner.run(null));
    }
}
