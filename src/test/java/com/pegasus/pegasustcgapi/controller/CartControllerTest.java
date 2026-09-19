package com.pegasus.pegasustcgapi.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CartItemResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CartService.AddResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("CartController")
class CartControllerTest {

    @Mock
    private CartService cartService;

    @InjectMocks
    private CartController cartController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(cartController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();
    }

    private static CartItemResponse mockItemResponse(long id, long listingId, int quantity, BigDecimal price, boolean priceChanged) {
        return new CartItemResponse(
                id, 1L, listingId, quantity, price, price, priceChanged, true, 10, 99L, 1L,
                CardCondition.NM, "THB", OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("POST /api/v1/cart/items returns X-Cart-Session header for guest")
    void guestAddItemSetsCartSessionHeader() throws Exception {
        String sessionKey = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        CartItemResponse item = mockItemResponse(100L, 5L, 2, new BigDecimal("120.00"), false);
        given(cartService.addItem(any(), any(), any(CartItemRequest.class)))
                .willReturn(new AddResult(item, sessionKey));

        mockMvc.perform(post(ApiPaths.CART_ITEMS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "listingId": 5,
                                    "quantity": 2
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Cart-Session", sessionKey))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.listingId").value(5))
                .andExpect(jsonPath("$.data.quantity").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/cart/items returns cart items")
    void getCartItemsReturnsList() throws Exception {
        CartItemResponse item1 = mockItemResponse(100L, 5L, 2, new BigDecimal("120.00"), false);
        CartItemResponse item2 = mockItemResponse(101L, 6L, 1, new BigDecimal("80.00"), true);
        given(cartService.getCartItems(any(), eq("guest-session")))
                .willReturn(List.of(item1, item2));

        mockMvc.perform(get(ApiPaths.CART_ITEMS)
                        .header("X-Cart-Session", "guest-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].listingId").value(5))
                .andExpect(jsonPath("$.data[0].priceChanged").value(false))
                .andExpect(jsonPath("$.data[1].listingId").value(6))
                .andExpect(jsonPath("$.data[1].priceChanged").value(true));
    }

    @Test
    @DisplayName("DELETE /api/v1/cart/items/{itemId} successfully deletes item")
    void deleteCartItemSucceeds() throws Exception {
        mockMvc.perform(delete(ApiPaths.CART_ITEMS + "/100")
                        .header("X-Cart-Session", "guest-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Cart item removed"));
    }

    @Test
    @DisplayName("DELETE /api/v1/cart/items/{itemId} returns 404 when item not found")
    void deleteNonExistentCartItemReturns404() throws Exception {
        willThrow(new NotFoundException(ErrorCode.CART_ITEM_NOT_FOUND))
                .given(cartService).removeItem(any(), any(), eq(999L));

        mockMvc.perform(delete(ApiPaths.CART_ITEMS + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.code").value(ErrorCode.CART_ITEM_NOT_FOUND.name()));
    }
}
