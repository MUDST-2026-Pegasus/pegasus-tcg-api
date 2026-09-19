package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CheckoutRequest;
import com.pegasus.pegasustcgapi.dto.CheckoutResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
            @ApiResponse(responseCode = "201", description = "Order created successfully"),
            @ApiResponse(responseCode = "400", description = "Idempotency key missing or invalid request"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "409", description = "Cart empty, price changed, listing unpurchasable, cannot buy own listing, or idempotency conflict")
    })
    @PostMapping
    public ResponseEntity<ApiResult<CheckoutResponse>> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Cart-Session", required = false) String sessionKey,
            @Valid @RequestBody(required = false) CheckoutRequest request,
            AuthPrincipal principal) {

        CheckoutResponse response = checkoutService.checkout(principal, idempotencyKey, sessionKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Order placed successfully", response));
    }
}
