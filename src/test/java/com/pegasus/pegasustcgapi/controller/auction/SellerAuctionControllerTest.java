package com.pegasus.pegasustcgapi.controller.auction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.controller.auction.AuctionSamples;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.CreateAuction;
import com.pegasus.pegasustcgapi.dto.auction.AuctionRequests.UpdateAuction;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.EligibleUnitPage;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.SellerAuctionDetail;
import com.pegasus.pegasustcgapi.dto.auction.SellerAuctionViews.SellerAuctionPage;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auction.AuctionStatus;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.auction.SellerAuctionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

@ExtendWith(MockitoExtension.class)
class SellerAuctionControllerTest {

    private static final String BASE_URL = ApiPaths.SELLERS_ME_AUCTIONS;
    private static final long USER_ID = 42L;

    @Mock
    private SellerAuctionService service;

    private MockMvc mvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SellerAuctionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        Jwt jwt = Jwt.withTokenValue("test-seller-token")
                .header("alg", "HS256")
                .subject(String.valueOf(USER_ID))
                .claim(AuthClaims.EMAIL, "seller@example.com")
                .claim(AuthClaims.USERNAME, "seller")
                .claim(AuthClaims.ROLES, List.of("SELLER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /sellers/me/auctions delegates to service.list")
    void listAuctions() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SellerAuctionPage page = AuctionSamples.sellerAuctions(now, 0, 20);
        when(service.list(eq(USER_ID), eq(AuctionStatus.LIVE), eq("Pikachu"), eq(0), eq(20))).thenReturn(page);

        mvc.perform(get(BASE_URL)
                        .param("status", "LIVE")
                        .param("q", "Pikachu")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).list(eq(USER_ID), eq(AuctionStatus.LIVE), eq("Pikachu"), eq(0), eq(20));
    }

    @Test
    @DisplayName("GET /sellers/me/auctions/eligible-units delegates to service.eligibleUnits")
    void eligibleUnits() throws Exception {
        EligibleUnitPage page = AuctionSamples.eligibleUnits(0, 20);
        when(service.eligibleUnits(eq(USER_ID), eq("Charizard"), eq((short) 1), eq(CardCondition.NM), eq(0), eq(20)))
                .thenReturn(page);

        mvc.perform(get(BASE_URL + "/eligible-units")
                        .param("q", "Charizard")
                        .param("gameId", "1")
                        .param("condition", "NM")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).eligibleUnits(eq(USER_ID), eq("Charizard"), eq((short) 1), eq(CardCondition.NM), eq(0), eq(20));
    }

    @Test
    @DisplayName("POST /sellers/me/auctions creates auction and returns 201 Created")
    void createAuction() throws Exception {
        UUID unitUid = UUID.randomUUID();
        CreateAuction request = new CreateAuction(
                unitUid,
                new BigDecimal("1000.00"),
                new BigDecimal("50.00"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3),
                new BigDecimal("1800.00"),
                "Note");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SellerAuctionDetail created = AuctionSamples.sellerDetail(101L, now);
        when(service.create(eq(USER_ID), any(CreateAuction.class))).thenReturn(created);

        mvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(101));

        verify(service).create(eq(USER_ID), any(CreateAuction.class));
    }

    @Test
    @DisplayName("GET /sellers/me/auctions/{id} returns auction details")
    void getAuction() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SellerAuctionDetail detail = AuctionSamples.sellerDetail(101L, now);
        when(service.get(eq(USER_ID), eq(101L))).thenReturn(detail);

        mvc.perform(get(BASE_URL + "/{id}", 101L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));

        verify(service).get(eq(USER_ID), eq(101L));
    }

    @Test
    @DisplayName("GET /sellers/me/auctions/{id} returns 404 when auction belongs to another shop")
    void getOtherShopAuctionReturns404() throws Exception {
        when(service.get(eq(USER_ID), eq(999L)))
                .thenThrow(new NotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Auction not found"));

        mvc.perform(get(BASE_URL + "/{id}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("AUCTION_NOT_FOUND"));

        verify(service).get(eq(USER_ID), eq(999L));
    }

    @Test
    @DisplayName("POST /sellers/me/auctions returns 403 when seller is not verified")
    void createAuctionUnverifiedSellerReturns403() throws Exception {
        UUID unitUid = UUID.randomUUID();
        CreateAuction request = new CreateAuction(
                unitUid,
                new BigDecimal("1000.00"),
                new BigDecimal("50.00"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3),
                new BigDecimal("1800.00"),
                "Note");

        when(service.create(eq(USER_ID), any(CreateAuction.class)))
                .thenThrow(new ForbiddenException(ErrorCode.SELLER_NOT_VERIFIED, "Seller profile is not verified"));

        mvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.code").value("SELLER_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("PATCH /sellers/me/auctions/{id} updates auction settings")
    void updateAuction() throws Exception {
        UpdateAuction request = new UpdateAuction(new BigDecimal("2500.00"), "Updated note");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SellerAuctionDetail updated = AuctionSamples.sellerDetail(101L, now);
        when(service.update(eq(USER_ID), eq(101L), any(UpdateAuction.class))).thenReturn(updated);

        mvc.perform(patch(BASE_URL + "/{id}", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));

        verify(service).update(eq(USER_ID), eq(101L), any(UpdateAuction.class));
    }

    @Test
    @DisplayName("POST /sellers/me/auctions/{id}/cancel cancels auction")
    void cancelAuction() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SellerAuctionDetail cancelled = AuctionSamples.sellerDetail(101L, now);
        when(service.cancel(eq(USER_ID), eq(101L))).thenReturn(cancelled);

        mvc.perform(post(BASE_URL + "/{id}/cancel", 101L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));

        verify(service).cancel(eq(USER_ID), eq(101L));
    }
}
