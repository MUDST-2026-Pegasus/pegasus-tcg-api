package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Buyer;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Summary;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.SellerOrderDeskService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("SellerOrderController")
class SellerOrderControllerTest {

    @Mock
    private SellerOrderDeskService desk;

    @InjectMocks
    private SellerOrderController sellerOrderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sellerOrderController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        setAuthenticatedUser(99L, "seller@example.com", "seller");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void setAuthenticatedUser(long userId, String email, String username) {
        Jwt jwt = Jwt.withTokenValue("mock-access-token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .claim(AuthClaims.EMAIL, email)
                .claim(AuthClaims.USERNAME, username)
                .claim(AuthClaims.ROLES, List.of("SELLER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static SellerOrderDetailsResponse mockSellerOrderDetails(long id, String status) {
        return new SellerOrderDetailsResponse(
                id, 1L, 77L, "ORD-1-S77", status,
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("90.00"),
                null, null, null, null, null, null, null,
                OffsetDateTime.now(),
                List.of(), List.of(), List.of());
    }

    private static Summary mockSummary(long id, String status) {
        return new Summary(mockSellerOrderDetails(id, status), "PGS-20261013-000001",
                new Buyer(5L, "buyer_one", "Buyer One", null), OffsetDateTime.now(), OffsetDateTime.now().plusDays(2),
                "{\"recipientName\":\"Buyer One\"}", null);
    }

    private static Detail mockDetail(long id, String status) {
        return new Detail(mockSummary(id, status), null, new BigDecimal("60.0000"), 1);
    }

    @Test
    @DisplayName("GET /api/v1/sellers/me/orders passes every filter through and returns rows with the buyer")
    void listSellerOrders_Success() throws Exception {
        given(desk.list(any(), eq(List.of("PAID", "PREPARING")), eq("buyer"), eq("PAID_OLDEST"), eq(0), eq(20)))
                .willReturn(PageResponse.of(List.of(mockSummary(10L, "PAID")), 0, 20, 1));

        mockMvc.perform(get(ApiPaths.SELLERS_ME_ORDERS)
                        .param("status", "PAID,PREPARING")
                        .param("q", "buyer")
                        .param("sort", "PAID_OLDEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].order.id").value(10))
                .andExpect(jsonPath("$.data.items[0].order.sellerOrderNumber").value("ORD-1-S77"))
                .andExpect(jsonPath("$.data.items[0].buyer.username").value("buyer_one"))
                .andExpect(jsonPath("$.data.items[0].orderNumber").value("PGS-20261013-000001"));
    }

    @Test
    @DisplayName("GET /api/v1/sellers/me/orders/counts returns a count per status")
    void counts_Success() throws Exception {
        given(desk.counts(any())).willReturn(Map.of("PAID", 3L, "SHIPPED", 1L));

        mockMvc.perform(get(ApiPaths.SELLERS_ME_ORDERS + "/counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.PAID").value(3))
                .andExpect(jsonPath("$.data.SHIPPED").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/sellers/me/orders/{id} returns 404 when not found")
    void getSellerOrder_NotFound() throws Exception {
        given(desk.detail(any(), eq(999L)))
                .willThrow(new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.SELLERS_ME_ORDERS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/accept returns the order as PREPARING")
    void acceptOrder_Success() throws Exception {
        given(desk.accept(any(), eq(10L))).willReturn(mockDetail(10L, "PREPARING"));

        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.order.status").value("PREPARING"))
                .andExpect(jsonPath("$.data.costTotal").value(60.0));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/accept is a 409 when the order is not PAID")
    void acceptOrder_Conflict() throws Exception {
        given(desk.accept(any(), eq(10L))).willThrow(new ConflictException(ErrorCode.ORDER_STATUS_TRANSITION));

        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("ORDER_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/ship returns 200 on success")
    void shipOrder_Success() throws Exception {
        given(desk.ship(any(), eq(10L), any()))
                .willReturn(mockDetail(10L, "SHIPPED"));

        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"Thailand Post\",\"trackingNumber\":\"TH1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.order.status").value("SHIPPED"));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/ship fails validation when carrierName is blank")
    void shipOrder_ValidationFailed() throws Exception {
        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/ship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierName\":\"\",\"trackingNumber\":\"TH1234567890\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/cancel passes the reason through")
    void cancelOrder_Success() throws Exception {
        given(desk.cancel(any(), eq(10L), eq("Card was damaged"))).willReturn(mockDetail(10L, "CANCELLED"));

        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Card was damaged\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.order.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/sellers/me/orders/{id}/cancel needs a reason")
    void cancelOrder_ReasonRequired() throws Exception {
        mockMvc.perform(post(ApiPaths.SELLERS_ME_ORDERS + "/10/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
