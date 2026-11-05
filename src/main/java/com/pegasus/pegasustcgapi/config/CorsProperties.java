package com.pegasus.pegasustcgapi.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Browser clients live on a different origin from this API, so the allowed list
 * is configuration rather than a constant.
 *
 * <p>{@code allowedOriginPatterns} covers hosts that change per deploy, e.g. Cloudflare
 * Worker previews at {@code https://*.pegasus-dev-teams.workers.dev}. Both lists feed the
 * CORS filter and the refresh-origin check, so they always agree.
 */
@ConfigurationProperties("pegasus.cors")
public record CorsProperties(List<String> allowedOrigins, List<String> allowedOriginPatterns) {

    public CorsProperties {
        allowedOrigins = clean(allowedOrigins);
        allowedOriginPatterns = clean(allowedOriginPatterns);
        if (allowedOrigins.contains("*") || allowedOriginPatterns.contains("*")) {
            throw new IllegalStateException("Credentialed CORS requires explicit allowed origins");
        }
    }

    public CorsProperties(List<String> allowedOrigins) {
        this(allowedOrigins, List.of());
    }

    /** Applies the origins and patterns to a Spring CORS configuration. */
    public CorsConfiguration applyTo(CorsConfiguration config) {
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        return config;
    }

    /** True when the browser origin is in the list or matches a pattern. */
    public boolean allows(String origin) {
        return origin != null && applyTo(new CorsConfiguration()).checkOrigin(origin) != null;
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
