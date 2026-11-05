package com.pegasus.pegasustcgapi.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Browser clients live on a different origin from this API, so the allowed list
 * is configuration rather than a constant.
 */
@ConfigurationProperties("pegasus.cors")
public record CorsProperties(List<String> allowedOrigins) {

    // Two constructors: Spring must be told which one binds the properties.
    @ConstructorBinding
    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
