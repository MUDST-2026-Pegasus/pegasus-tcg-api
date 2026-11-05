package com.pegasus.pegasustcgapi.controller.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.ReorderItemsResponse;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.dto.payment.PaymentInfoResponse;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.payment.PaymentService;
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
@DisplayName("OrderController")
class OrderControllerTest {

    @Mock
    private OrderLifecycleService orderLifecycleService;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderController orderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(orderController)
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

    private static OrderDetailsResponse mockOrderDetails(long orderId, String status) {
        return new OrderDetailsResponse(
                orderId,
                "ORD-" + orderId,
                42L,
                status,
                "THB",
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                1L,
                "{}",
                "Please deliver carefully",
                OffsetDateTime.now(),
                null, null, null,
                List.of(),
                false,
                null);
    }

    @Test
    @DisplayName("GET /api/v1/orders returns list of orders")
    void listOrders_Success() throws Exception {
        given(orderLifecycleService.listBuyerOrders(any(), anyInt(), anyInt()))
                .willReturn(PageResponse.of(List.of(mockOrderDetails(1L, "PENDING_PAYMENT")), 0, 20, 1));

        mockMvc.perform(get(ApiPaths.ORDERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(1))
                .andExpect(jsonPath("$.data.items[0].orderNumber").value("ORD-1"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} returns 404 if order not found")
    void getOrder_NotFound() throws Exception {
        given(orderLifecycleService.getBuyerOrder(any(), eq(999L)))
                .willThrow(new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.ORDERS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/cancel returns 200 on successful cancellation")
    void cancelOrder_Success() throws Exception {
        given(orderLifecycleService.cancelBuyerOrder(any(), eq(1L), any()))
                .willReturn(mockOrderDetails(1L, "CANCELLED"));

        mockMvc.perform(post(ApiPaths.ORDERS + "/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed mind\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/cancel returns 409 if cancel window closed")
    void cancelOrder_WindowClosed() throws Exception {
        given(orderLifecycleService.cancelBuyerOrder(any(), eq(1L), any()))
                .willThrow(new ConflictException(ErrorCode.CANCEL_WINDOW_CLOSED));

        mockMvc.perform(post(ApiPaths.ORDERS + "/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Late cancellation\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("CANCEL_WINDOW_CLOSED"));
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/confirm-received returns 200 on success")
    void confirmReceived_Success() throws Exception {
        given(orderLifecycleService.confirmBuyerOrderReceived(any(), eq(1L)))
                .willReturn(mockOrderDetails(1L, "COMPLETED"));

        mockMvc.perform(post(ApiPaths.ORDERS + "/1/confirm-received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id}/reorder-items returns 200 on success")
    void getReorderItems_Success() throws Exception {
        ReorderItemsResponse response = new ReorderItemsResponse(
                1L,
                "ORD-1",
                List.of(new ReorderItemsResponse.ReorderItemDto(
                        10L, 20L, "charizard-ex", "Charizard EX", "EN / Foil", "SAR",
                        "https://example.com/img.png", "NM", new BigDecimal("150.00"), 1, "Card Kingdom"
                ))
        );
        given(orderLifecycleService.getReorderItems(eq(1L), eq(42L)))
                .willReturn(response);

        mockMvc.perform(get(ApiPaths.ORDERS + "/1/reorder-items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(1))
                .andExpect(jsonPath("$.data.orderNumber").value("ORD-1"))
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(10))
                .andExpect(jsonPath("$.data.items[0].catalogVariantId").value(20))
                .andExpect(jsonPath("$.data.items[0].productSlug").value("charizard-ex"))
                .andExpect(jsonPath("$.data.items[0].productName").value("Charizard EX"))
                .andExpect(jsonPath("$.data.items[0].rarity").value("SAR"))
                .andExpect(jsonPath("$.data.items[0].originalSellerName").value("Card Kingdom"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id}/reorder-items returns 404 when order not found")
    void getReorderItems_NotFound() throws Exception {
        given(orderLifecycleService.getReorderItems(eq(999L), eq(42L)))
                .willThrow(new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.ORDERS + "/999/reorder-items"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id}/reorder-items returns 400 when order not completed")
    void getReorderItems_NotCompleted() throws Exception {
        given(orderLifecycleService.getReorderItems(eq(1L), eq(42L)))
                .willThrow(new BadRequestException(ErrorCode.INVALID_ORDER_STATUS, "Only completed orders can be reordered"));

        mockMvc.perform(get(ApiPaths.ORDERS + "/1/reorder-items"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("INVALID_ORDER_STATUS"));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{orderId}/payment-info returns 200 with QR payload and transfer details")
    void getPaymentInfo_Success() throws Exception {
        OffsetDateTime expiresAt = OffsetDateTime.parse("2026-11-06T12:00:00Z");
        PaymentInfoResponse response = new PaymentInfoResponse(
                "00020101021229370016A0000006770101110113006694776111953037645406673.005802TH5911PEGASUS TCG6007Bangkok6304C2AF",
                "094-xxx-1119",
                "ธนาคารไทยพาณิชย์ (SCB)",
                "PEGASUS TCG",
                new BigDecimal("673.00"),
                expiresAt
        );

        given(paymentService.getPaymentInfo(any(), eq(100L)))
                .willReturn(response);

        mockMvc.perform(get(ApiPaths.ORDERS + "/100/payment-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.qrPayload").value(response.qrPayload()))
                .andExpect(jsonPath("$.data.promptpayId").value("094-xxx-1119"))
                .andExpect(jsonPath("$.data.bankName").value("ธนาคารไทยพาณิชย์ (SCB)"))
                .andExpect(jsonPath("$.data.accountName").value("PEGASUS TCG"))
                .andExpect(jsonPath("$.data.amount").value(673.00))
                .andExpect(jsonPath("$.data.expiresAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/orders/{orderId}/payment-info returns 404 when order not found")
    void getPaymentInfo_NotFound() throws Exception {
        given(paymentService.getPaymentInfo(any(), eq(999L)))
                .willThrow(new NotFoundException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.ORDERS + "/999/payment-info"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value("ORDER_NOT_FOUND"));
    }
}
