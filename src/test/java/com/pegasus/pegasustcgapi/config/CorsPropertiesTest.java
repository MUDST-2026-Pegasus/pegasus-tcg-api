package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CorsProperties")
class CorsPropertiesTest {

    private final CorsProperties props = new CorsProperties(
            List.of("https://pegasus-dev.gameinwza.com"),
            List.of("https://*.pegasus-dev-teams.workers.dev"));

    @Test
    @DisplayName("allows exact origins and Worker preview subdomains")
    void allowsExactAndPattern() {
        assertThat(props.allows("https://pegasus-dev.gameinwza.com")).isTrue();
        assertThat(props.allows("https://tcg.pegasus-dev-teams.workers.dev")).isTrue();
        assertThat(props.allows("https://feat-x-tcg.pegasus-dev-teams.workers.dev")).isTrue();
    }

    @Test
    @DisplayName("rejects other hosts, look-alikes and missing origin")
    void rejectsOthers() {
        assertThat(props.allows("https://evil.workers.dev")).isFalse();
        assertThat(props.allows("https://pegasus-dev-teams.workers.dev.evil.com")).isFalse();
        assertThat(props.allows("http://tcg.pegasus-dev-teams.workers.dev")).isFalse();
        assertThat(props.allows(null)).isFalse();
    }

    @Test
    @DisplayName("ignores blank entries and refuses a bare wildcard")
    void blanksAndWildcard() {
        assertThat(new CorsProperties(List.of(""), List.of(" ")).allowedOriginPatterns()).isEmpty();
        assertThatThrownBy(() -> new CorsProperties(List.of(), List.of("*")))
                .isInstanceOf(IllegalStateException.class);
    }
}
