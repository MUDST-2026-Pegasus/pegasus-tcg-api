package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CartItemResponse;
import com.pegasus.pegasustcgapi.dto.CartResponse;
import com.pegasus.pegasustcgapi.dto.UpdateCartItemRequest;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CartService.AddResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for shopping cart operations.
 *
 * <p>Every handler takes {@code Optional<AuthPrincipal>}: that is how a route says
 * it serves guests as well as signed-in callers. The argument resolver reads the
 * parameter's type rather than the request's path, so a route only admits anonymous
 * callers when its own signature says so.
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
            Optional<AuthPrincipal> principal) {

        AddResult result = cartService.addItem(principal.orElse(null), sessionKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.sessionKey() != null) {
            builder.header("X-Cart-Session", result.sessionKey());
        }
        return builder.body(ApiResult.success("Item added to cart", result.item()));
    }

    @Operation(summary = "View cart items", description = "Retrieves all items in the current cart with latest pricing and price-change indicators.")
    @ApiResponse(responseCode = "200", description = "Cart items retrieved")
    @GetMapping("/items")
    public ResponseEntity<ApiResult<List<CartItemResponse>>> getCartItems(
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            Optional<AuthPrincipal> principal) {

        List<CartItemResponse> items = cartService.getCartItems(principal.orElse(null), sessionKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (principal.isEmpty() && sessionKey != null && !sessionKey.isBlank()) {
            builder.header("X-Cart-Session", sessionKey);
        }
        return builder.body(ApiResult.success(items));
    }

    @Operation(summary = "View cart summary", description = "Retrieves full cart information including items and total.")
    @ApiResponse(responseCode = "200", description = "Cart retrieved")
    @GetMapping
    public ResponseEntity<ApiResult<CartResponse>> getCart(
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            Optional<AuthPrincipal> principal) {

        CartResponse response = cartService.getCart(principal.orElse(null), sessionKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (principal.isEmpty() && response.sessionKey() != null) {
            builder.header("X-Cart-Session", response.sessionKey());
        }
        return builder.body(ApiResult.success(response));
    }

    @Operation(summary = "Update cart item quantity", description = "Updates the quantity of an item in the current cart. Supports guest sessions and authenticated users.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cart item quantity updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload or quantity < 1"),
            @ApiResponse(responseCode = "404", description = "Cart item not found in current cart"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock or listing not purchasable")
    })
    @PutMapping("/items/{itemId}")
    public ResponseEntity<ApiResult<CartItemResponse>> updateItemQuantity(
            @PathVariable long itemId,
            @Valid @RequestBody UpdateCartItemRequest request,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            Optional<AuthPrincipal> principal) {

        CartItemResponse response = cartService.updateItemQuantity(principal.orElse(null), sessionKey, itemId, request.quantity());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (principal.isEmpty() && sessionKey != null && !sessionKey.isBlank()) {
            builder.header("X-Cart-Session", sessionKey);
        }
        return builder.body(ApiResult.success("Cart item quantity updated", response));
    }

    @Operation(summary = "Partially update cart item quantity", description = "Updates the quantity of an item in the current cart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cart item quantity updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload or quantity < 1"),
            @ApiResponse(responseCode = "404", description = "Cart item not found in current cart"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock or listing not purchasable")
    })
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<ApiResult<CartItemResponse>> patchItemQuantity(
            @PathVariable long itemId,
            @Valid @RequestBody UpdateCartItemRequest request,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            Optional<AuthPrincipal> principal) {

        return updateItemQuantity(itemId, request, sessionKey, principal);
    }

    @Operation(summary = "Remove cart item", description = "Deletes an item from the current cart.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item removed from cart"),
            @ApiResponse(responseCode = "404", description = "Cart item not found")
    })
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<ApiResult<Void>> removeItem(
            @PathVariable long itemId,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            Optional<AuthPrincipal> principal) {

        cartService.removeItem(principal.orElse(null), sessionKey, itemId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (principal.isEmpty() && sessionKey != null && !sessionKey.isBlank()) {
            builder.header("X-Cart-Session", sessionKey);
        }
        return builder.body(ApiResult.success("Cart item removed", null));
    }
}
