package com.pegasus.pegasustcgapi.controller.catalog;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import java.time.Instant;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.service.catalog.CatalogCardEffectService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogImageService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogProductService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogVariantService;
import com.pegasus.pegasustcgapi.service.listing.AdminCardListingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** TCG-538: an administrator deletes a product catalogued by mistake. */
@ExtendWith(MockitoExtension.class)
class AdminCatalogProductControllerTest {

    private static final String PRODUCTS = ApiPaths.ADMIN + "/catalog/products";

    @Mock CatalogProductService products;
    @Mock CatalogVariantService variants;
    @Mock CatalogImageService images;
    @Mock CatalogSearchService search;
    @Mock CatalogCardEffectService effects;
    @Mock AdminCardListingService listings;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminCatalogProductController(products, variants, images, search, effects, listings))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("test-admin").header("alg", "HS256").subject("41")
                .claim(AuthClaims.EMAIL, "admin@example.com")
                .claim(AuthClaims.USERNAME, "admin")
                .claim(AuthClaims.ROLES, List.of("ADMIN"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deletingAnUnusedProductReturnsSuccess() throws Exception {
        mvc.perform(delete(PRODUCTS + "/501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Product deleted"));
        verify(products).delete(501L, 41L);
    }

    @Test
    void productInUseIsAConflict() throws Exception {
        doThrow(new ConflictException(ErrorCode.PRODUCT_IN_USE)).when(products).delete(eq(501L), anyLong());

        mvc.perform(delete(PRODUCTS + "/501"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("PRODUCT_IN_USE"));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        doThrow(new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND)).when(products).delete(eq(999L), anyLong());

        mvc.perform(delete(PRODUCTS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("PRODUCT_NOT_FOUND"));
    }
}
