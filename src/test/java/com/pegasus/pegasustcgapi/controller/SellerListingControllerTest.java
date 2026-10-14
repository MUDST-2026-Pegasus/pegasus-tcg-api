package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.ListingService;
import com.pegasus.pegasustcgapi.service.ListingUnitService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** RQ-1/CR5: seller listing HTTP input and ownership boundaries. */
@ExtendWith(MockitoExtension.class)
class SellerListingControllerTest {

    private static final String LISTINGS = ApiPaths.SELLERS_ME + "/listings";

    @Mock ListingService listings;
    @Mock ListingUnitService units;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SellerListingController(listings, units))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("test-seller").header("alg", "HS256").subject("73")
                .claim(AuthClaims.EMAIL, "seller@example.com")
                .claim(AuthClaims.USERNAME, "seller")
                .claim(AuthClaims.ROLES, List.of("SELLER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listPassesSellerIdentityAndAllFilters() throws Exception {
        when(listings.mine(eq(73L), eq(ListingStatus.ACTIVE), eq(9L), eq(CardCondition.NM),
                eq("Charizard"), eq("price_asc"), eq(2), eq(5)))
                .thenReturn(PageResponse.of(List.of(), 2, 5, 11));

        mvc.perform(get(LISTINGS).param("status", "ACTIVE").param("variantId", "9")
                        .param("condition", "NM").param("q", "Charizard")
                        .param("sort", "price_asc").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(3));
    }

    @Test
    void listUsesFirstPageOfTwentyByDefault() throws Exception {
        when(listings.mine(eq(73L), eq(null), eq(null), eq(null), eq(null), eq(null), eq(0), eq(20)))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mvc.perform(get(LISTINGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void unknownStatusIsA400BeforeTheServiceRuns() throws Exception {
        mvc.perform(get(LISTINGS).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.violations[0].field").value("status"));
        verifyNoInteractions(listings);
    }

    @Test
    void unauthenticatedSellerCannotListInventory() throws Exception {
        SecurityContextHolder.clearContext();

        mvc.perform(get(LISTINGS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(listings);
    }

    @Test
    void unverifiedSellerCannotChangeStatus() throws Exception {
        when(listings.changeStatus(73L, 12L, ListingStatus.ACTIVE))
                .thenThrow(new ApiException(ErrorCode.SELLER_NOT_VERIFIED));

        mvc.perform(patch(LISTINGS + "/12/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("SELLER_NOT_VERIFIED"));
    }

    @Test
    void missingStatusIsRejectedBeforeTheServiceRuns() throws Exception {
        mvc.perform(patch(LISTINGS + "/12/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("status"));
        verifyNoInteractions(listings);
    }

    @Test
    void invalidPricePrecisionIsRejectedBeforeTheServiceRuns() throws Exception {
        mvc.perform(patch(LISTINGS + "/12/price").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricingMode\":\"MANUAL\",\"price\":12.345}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("price"));
        verifyNoInteractions(listings);
    }

    @Test
    void malformedUnitIdentifierIsRejectedBeforeTheServiceRuns() throws Exception {
        mvc.perform(get(LISTINGS + "/12/units/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("unitUid"));
        verifyNoInteractions(units);
    }

    @Test
    void removingCardPreservesTheSellerAndListingScope() throws Exception {
        UUID uid = UUID.fromString("019f09c4-2e8a-7e5d-99b2-0443849739d3");

        mvc.perform(delete(LISTINGS + "/12/units/" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Card taken off the listing"));
        verify(units).removeFromListing(73L, 12L, uid);
    }
}
