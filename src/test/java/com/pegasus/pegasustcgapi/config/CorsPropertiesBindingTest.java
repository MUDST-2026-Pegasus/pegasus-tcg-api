package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@DisplayName("CorsProperties binding")
class CorsPropertiesBindingTest {

    @Test
    @DisplayName("binds origins and patterns from configuration")
    void bindsFromConfiguration() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "pegasus.cors.allowed-origins", "https://a.example,https://b.example",
                "pegasus.cors.allowed-origin-patterns", "https://*.pegasus-dev-teams.workers.dev")));

        CorsProperties props = binder.bind("pegasus.cors", CorsProperties.class).get();

        assertThat(props.allowedOrigins()).containsExactly("https://a.example", "https://b.example");
        assertThat(props.allows("https://x.pegasus-dev-teams.workers.dev")).isTrue();
    }
}
