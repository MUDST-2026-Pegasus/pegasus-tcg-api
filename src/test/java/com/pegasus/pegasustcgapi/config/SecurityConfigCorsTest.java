package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@DisplayName("SecurityConfig CORS")
class SecurityConfigCorsTest {

    @Test
    @DisplayName("allows Idempotency-Key and X-Cart-Session headers in CORS configuration")
    void allowedHeadersIncludeIdempotencyAndCartSession() {
        SecurityConfig config = new SecurityConfig();
        CorsProperties properties = new CorsProperties(List.of("http://localhost:3000"));

        CorsConfigurationSource source = config.corsConfigurationSource(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", ApiPaths.API_V1 + "/cart");
        CorsConfiguration corsConfig = source.getCorsConfiguration(request);

        assertThat(corsConfig).isNotNull();
        assertThat(corsConfig.getAllowedHeaders()).contains(
                "Authorization",
                "Content-Type",
                "Accept",
                "Idempotency-Key",
                "X-Cart-Session");
    }
}
