package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.OrderItemResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse.SellerOrderResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
@DisplayName("CheckoutController")
class CheckoutControllerTest {

    @Mock
    private CheckoutService checkoutService;

    @InjectMocks
    private CheckoutController checkoutController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(checkoutController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        setAuthenticatedUser(42L, "buyer@example.com", "buyer");
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
                .claim(AuthClaims.ROLES, List.of("BUYER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static CheckoutResponse mockResponse(long orderId, String orderNumber) {
        OrderItemResponse item = new OrderItemResponse(
                100L, 10L, 1L, "Charizard", "EN / Foil", "NM", 1,
                new BigDecimal("150.00"), new BigDecimal("150.00"), List.of(1001L));
        SellerOrderResponse sellerOrder = new SellerOrderResponse(
                10L, 77L, orderNumber + "-S77", "PENDING_PAYMENT",
                new BigDecimal("150.00"), BigDecimal.ZERO, new BigDecimal("150.00"),
                new BigDecimal("15.00"), new BigDecimal("135.00"), List.of(item));
        return new CheckoutResponse(
                orderId, orderNumber, 42L, "PENDING_PAYMENT", "THB",
                new BigDecimal("150.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("150.00"),
                OffsetDateTime.now(), List.of(sellerOrder));
    }

    @Test
    @DisplayName("POST /api/v1/checkout returns 400 IDEMPOTENCY_KEY_REQUIRED when header missing")
    void missingIdempotencyKeyReturnsBadRequest() throws Exception {
        given(checkoutService.checkout(any(), eq(null), any(), any()))
                .willThrow(new BadRequestException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));

        mockMvc.perform(post(ApiPaths.CHECKOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("POST /api/v1/checkout returns 201 Created with order details on success")
    void successfulCheckoutReturnsCreated() throws Exception {
        CheckoutResponse response = mockResponse(1L, "ORD-2026-001");
        given(checkoutService.checkout(any(), eq("key-123"), eq("session-xyz"), any(CheckoutRequest.class)))
                .willReturn(response);

        mockMvc.perform(post(ApiPaths.CHECKOUT)
                        .header("Idempotency-Key", "key-123")
                        .header("X-Cart-Session", "session-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "shippingAddressId": 99,
                                    "buyerNote": "Leave at front door"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(1))
                .andExpect(jsonPath("$.data.orderNumber").value("ORD-2026-001"))
                .andExpect(jsonPath("$.data.grandTotal").value(150.00))
                .andExpect(jsonPath("$.data.sellerOrders[0].sellerProfileId").value(77))
                .andExpect(jsonPath("$.data.sellerOrders[0].items[0].productName").value("Charizard"));
    }

    @Test
    @DisplayName("POST /api/v1/checkout returns 200 OK with order details on idempotent replay")
    void replayedCheckoutReturnsOk() throws Exception {
        CheckoutResponse response = mockResponse(1L, "ORD-2026-001").withReplayed(true);
        given(checkoutService.checkout(any(), eq("key-replay"), any(), any()))
                .willReturn(response);

        mockMvc.perform(post(ApiPaths.CHECKOUT)
                        .header("Idempotency-Key", "key-replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(1))
                .andExpect(jsonPath("$.data.orderNumber").value("ORD-2026-001"))
                .andExpect(jsonPath("$.data.replayed").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/checkout returns 409 CART_EMPTY when cart has no items")
    void emptyCartReturnsConflict() throws Exception {
        given(checkoutService.checkout(any(), eq("key-empty"), any(), any()))
                .willThrow(new ConflictException(ErrorCode.CART_EMPTY));

        mockMvc.perform(post(ApiPaths.CHECKOUT)
                        .header("Idempotency-Key", "key-empty"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("CART_EMPTY"));
    }

    @Test
    @DisplayName("POST /api/v1/checkout returns 409 IDEMPOTENCY_KEY_CONFLICT on duplicate key from other user")
    void idempotencyConflictReturnsConflict() throws Exception {
        given(checkoutService.checkout(any(), eq("key-conflict"), any(), any()))
                .willThrow(new ConflictException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));

        mockMvc.perform(post(ApiPaths.CHECKOUT)
                        .header("Idempotency-Key", "key-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("POST /api/v1/checkout returns 401 UNAUTHENTICATED when principal is missing")
    void unauthenticatedReturnsUnauthorized() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post(ApiPaths.CHECKOUT)
                        .header("Idempotency-Key", "key-unauth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("UNAUTHENTICATED"));
    }
}
