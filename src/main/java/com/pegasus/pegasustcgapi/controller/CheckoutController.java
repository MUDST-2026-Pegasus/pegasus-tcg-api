package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.ShippingQuoteRequest;
import com.pegasus.pegasustcgapi.dto.ShippingQuoteResponse;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for checkout and order placement.
 */
@Tag(name = "Checkout", description = "Shopping cart checkout and order placement")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.CHECKOUT)
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Operation(summary = "Checkout active cart", description = "Converts items in the buyer's cart into a sales order and seller orders with inventory reservation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order already placed with this idempotency key (replayed response)"),
            @ApiResponse(responseCode = "201", description = "Order created successfully"),
            @ApiResponse(responseCode = "400", description = "Missing idempotency key, SHIPPING_OPTION_INVALID, SELLER_SHIPPING_UNAVAILABLE (details.sellerProfileId), or SHIPPING_ADDRESS_REQUIRED"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "409", description = "Cart empty, price changed, listing unpurchasable, cannot buy own listing, or idempotency conflict")
    })
    @PostMapping
    public ResponseEntity<ApiResult<CheckoutResponse>> checkout(
            @Parameter(description = "Required unique key for this checkout (1–64 characters). Reuse the same key to retrieve the original order.", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            @Valid @RequestBody(required = false) CheckoutRequest request,
            AuthPrincipal principal) {

        CheckoutResponse response = checkoutService.checkout(principal, idempotencyKey, sessionKey, request);
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = response.replayed() ? "Order retrieved successfully (idempotent replay)" : "Order placed successfully";
        return ResponseEntity.status(status)
                .body(ApiResult.success(message, response));
    }
    @Operation(summary = "Quote shipping for selected cart items", description = "Returns active choices per seller in display order, including computed fees and the default option. Null or empty cartItemIds selects the entire buyer cart. Quotes require no address, reserve no stock, and create no order. A seller with no active choices has options=[] and defaultOptionId=null.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shipping quotes grouped by seller"),
            @ApiResponse(responseCode = "400", description = "Invalid cart item identifiers"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "409", description = "Cart empty, item prices changed, own listing, or listing unavailable")
    })
    @PostMapping("/shipping-quotes")
    public ApiResult<List<ShippingQuoteResponse>> shippingQuotes(
            @Valid @RequestBody(required = false) ShippingQuoteRequest request,
            AuthPrincipal principal) {
        return ApiResult.success(checkoutService.shippingQuotes(principal, request));
    }
}
