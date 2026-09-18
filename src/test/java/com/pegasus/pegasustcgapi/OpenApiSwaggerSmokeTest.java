package com.pegasus.pegasustcgapi;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@DisplayName("OpenAPI & Swagger UI Endpoints")
class OpenApiSwaggerSmokeTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("serves OpenAPI 3 specification JSON at /v1/api-docs without authentication")
    void openApiDocsAvailableUnauthenticated() throws Exception {
        mockMvc.perform(get("/v1/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Pegasus TCG API"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$.info.description").value(org.hamcrest.Matchers.containsString("Total Endpoints:")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get.security[0].BearerAuth").exists());
    }

    @Test
    @DisplayName("redirects /v1/api-doc to /v1/api-docs")
    void openApiDocsAliasRedirects() throws Exception {
        mockMvc.perform(get("/v1/api-doc"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("redirects legacy /v3/api-docs to /v1/api-docs")
    void legacyOpenApiDocsRedirects() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("serves Swagger UI endpoint without requiring authentication")
    void swaggerUiAvailableUnauthenticated() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
