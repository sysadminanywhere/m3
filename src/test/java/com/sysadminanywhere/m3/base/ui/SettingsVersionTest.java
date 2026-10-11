package com.sysadminanywhere.m3.base.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SettingsVersionTest {
    @Test void settingsShowTheVersionGeneratedFromTheSharedBuildConfiguration() throws Exception {
        var properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
            assertThat(stream).isNotNull();
            var generated = new Properties();
            generated.load(stream);
            generated.forEach((key, value) -> properties.put(key.toString().substring("build.".length()), value));
        }
        Path source = Path.of("build-config/pom.xml");
        if (!Files.exists(source)) source = Path.of("../m3/build-config/pom.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(source.toFile());
        String expected = document.getElementsByTagName("revision").item(0).getTextContent();
        var build = new BuildProperties(properties);
        assertThat(build.getVersion()).isEqualTo(expected);
        var provider = provider();
        when(provider.getIfAvailable()).thenReturn(build);
        assertThat(new SettingsView(provider).getElement().getOuterHTML()).contains("About", "Version: " + expected);
    }

    @Test void directIdeLaunchWithoutBuildMetadataShowsDevelopmentBuild() {
        assertThat(new SettingsView(provider()).getElement().getOuterHTML()).contains("Development build");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<BuildProperties> provider() { return mock(ObjectProvider.class); }
}
