package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CartItemResponse;
import com.pegasus.pegasustcgapi.dto.CartResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CartService.AddResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for shopping cart operations.
 */
@Tag(name = "Cart", description = "Shopping cart and guest session management")
@RestController
@RequestMapping(ApiPaths.CART)
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @Operation(summary = "Add or update cart item", description = "Adds a listing to the cart or updates quantity. Creates guest session if unauthenticated.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Item added or updated in cart"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload"),
            @ApiResponse(responseCode = "409", description = "Listing not purchasable or seller cannot buy own listing")
    })
    @PostMapping("/items")
    public ResponseEntity<ApiResult<CartItemResponse>> addItem(
            @Valid @RequestBody CartItemRequest request,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            AuthPrincipal principal) {

        AddResult result = cartService.addItem(principal, sessionKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.sessionKey() != null) {
            builder.header("X-Cart-Session", result.sessionKey());
        }
        return builder.body(ApiResult.success("Item added to cart", result.item()));
    }

    @Operation(summary = "View cart items", description = "Retrieves all items in the current cart with latest pricing and price-change indicators.")
    @ApiResponse(responseCode = "200", description = "Cart items retrieved")
    @GetMapping("/items")
    public ApiResult<List<CartItemResponse>> getCartItems(
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            AuthPrincipal principal) {

        return ApiResult.success(cartService.getCartItems(principal, sessionKey));
    }

    @Operation(summary = "View cart summary", description = "Retrieves full cart information including items and total.")
    @ApiResponse(responseCode = "200", description = "Cart retrieved")
    @GetMapping
    public ApiResult<CartResponse> getCart(
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            AuthPrincipal principal) {

        return ApiResult.success(cartService.getCart(principal, sessionKey));
    }

    @Operation(summary = "Remove cart item", description = "Deletes an item from the current cart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item removed from cart"),
            @ApiResponse(responseCode = "404", description = "Cart item not found")
    })
    @DeleteMapping("/items/{itemId}")
    public ApiResult<Void> removeItem(
            @PathVariable long itemId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            AuthPrincipal principal) {

        cartService.removeItem(principal, sessionKey, itemId);
        return ApiResult.success("Cart item removed", null);
    }
}
