package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for buyer order queries and lifecycle actions (cancel, confirm receipt).
 */
@Tag(name = "Orders", description = "Buyer orders and lifecycle management")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ORDERS)
public class OrderController {

    private final OrderLifecycleService orderLifecycleService;

    public OrderController(OrderLifecycleService orderLifecycleService) {
        this.orderLifecycleService = orderLifecycleService;
    }

    @Operation(summary = "List buyer orders", description = "Retrieves all sales orders placed by the authenticated buyer.")
    @ApiResponse(responseCode = "200", description = "Orders retrieved")
    @GetMapping
    public ApiResult<List<OrderDetailsResponse>> list(AuthPrincipal principal) {
        return ApiResult.success(orderLifecycleService.listBuyerOrders(principal));
    }

    @Operation(summary = "Get order details", description = "Retrieves detailed order information including seller sub-orders and tracking.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order retrieved"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{id}")
    public ApiResult<OrderDetailsResponse> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(orderLifecycleService.getBuyerOrder(principal, id));
    }

    @Operation(summary = "Cancel order", description = "Cancels an order within the cancellation window before shipping.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled successfully"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order status transition forbidden or cancellation window expired")
    })
    @PostMapping("/{id}/cancel")
    public ApiResult<OrderDetailsResponse> cancel(
            @PathVariable long id,
            @RequestBody(required = false) CancelOrderRequest request,
            AuthPrincipal principal) {
        return ApiResult.success("Order cancelled successfully", orderLifecycleService.cancelBuyerOrder(principal, id, request));
    }

    @Operation(summary = "Confirm order receipt", description = "Confirms delivery of an order, releasing escrow and granting cards to collection.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order receipt confirmed and cards granted"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order status does not allow confirmation")
    })
    @PostMapping("/{id}/confirm-received")
    public ApiResult<OrderDetailsResponse> confirmReceived(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success("Order receipt confirmed successfully", orderLifecycleService.confirmBuyerOrderReceived(principal, id));
    }

    @Operation(summary = "Mark order paid", description = "Simulates successful order payment transition for testing and sandbox environments.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order marked as paid"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order cannot transition to PAID")
    })
    @PostMapping("/{id}/pay")
    public ApiResult<OrderDetailsResponse> pay(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success("Order payment processed successfully", orderLifecycleService.markOrderPaid(principal, id));
    }
}
