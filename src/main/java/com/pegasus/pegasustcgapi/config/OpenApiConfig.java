package com.pegasus.pegasustcgapi.config;

import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 and Swagger UI global configuration for Pegasus TCG API.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "BearerAuth";

    static {
        // Prevent AuthPrincipal handler method argument resolver from appearing as request parameter in Swagger UI
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthPrincipal.class);
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pegasus TCG API")
                        .description("RESTful API for Pegasus Trading Card Game Marketplace — Authentication, User Roles, Addresses, Seller Onboarding, Shipping, and Platform Settings.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("Pegasus Engineering Team")
                                .email("engineering@pegasus-tcg.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Enter your JWT access token (without 'Bearer ' prefix).")));
    }

    /**
     * Dynamically appends the total endpoint (operations) count to the API description
     * so it displays prominently at the top of the Swagger UI dashboard.
     */
    @Bean
    public OpenApiCustomizer endpointCounterCustomizer() {
        return openApi -> {
            if (openApi.getPaths() != null) {
                long totalEndpoints = openApi.getPaths().values().stream()
                        .mapToLong(pathItem -> pathItem.readOperations().size())
                        .sum();

                String baseDescription = openApi.getInfo().getDescription();
                openApi.getInfo().setDescription(
                        (baseDescription != null ? baseDescription : "")
                                + "\n\n---\n**Total Endpoints:** `" + totalEndpoints + "` operations");
            }
        };
    }
}
