package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.SellerOrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authenticated seller order queries and shipment fulfillment.
 */
@Tag(name = "Seller Orders", description = "Seller order fulfillment and shipments")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME_ORDERS)
public class SellerOrderController {

    private final OrderLifecycleService orderLifecycleService;

    public SellerOrderController(OrderLifecycleService orderLifecycleService) {
        this.orderLifecycleService = orderLifecycleService;
    }

    @Operation(summary = "List seller orders",
            description = "Retrieves a page of sub-orders for the authenticated seller, newest first. "
                    + "Pass status to narrow to what needs packing, e.g. PAID or PREPARING.")
    @ApiResponse(responseCode = "200", description = "Orders retrieved")
    @GetMapping
    public ApiResult<PageResponse<SellerOrderDetailsResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(orderLifecycleService.listSellerOrders(principal, status, page, size));
    }

    @Operation(summary = "Get seller order by ID", description = "Retrieves details of a specific seller sub-order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order retrieved"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{id}")
    public ApiResult<SellerOrderDetailsResponse> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(orderLifecycleService.getSellerOrder(principal, id));
    }

    @Operation(summary = "Ship order", description = "Marks a seller order as shipped, creates shipment tracking, commits inventory sale, and starts escrow auto-completion timer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order shipped successfully"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order status transition forbidden")
    })
    @PostMapping("/{id}/ship")
    public ApiResult<SellerOrderDetailsResponse> ship(
            @PathVariable long id,
            @Valid @RequestBody ShipOrderRequest request,
            AuthPrincipal principal) {
        return ApiResult.success("Order shipped successfully", orderLifecycleService.shipSellerOrder(principal, id, request));
    }
}
