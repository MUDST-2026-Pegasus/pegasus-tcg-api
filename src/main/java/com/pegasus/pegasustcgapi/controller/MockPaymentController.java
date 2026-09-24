package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marks an order paid without taking a payment, for local work and sandboxes.
 *
 * <p>This used to sit on {@code OrderController} and was therefore mapped in every
 * environment. Nothing in it verifies that money moved — the buyer asks, and the
 * order becomes PAID — so on a live deployment it is a way to have cards posted for
 * free. It is registered only when {@code pegasus.payment.mock-enabled} is true,
 * which defaults to false; a real payment provider would call the same transition
 * from a webhook after verifying the provider's signature.
 */
@Tag(name = "Orders", description = "Mock payment transition for development environments")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ORDERS)
@ConditionalOnProperty(name = "pegasus.payment.mock-enabled", havingValue = "true")
public class MockPaymentController {

    private final OrderLifecycleService orderLifecycleService;

    public MockPaymentController(OrderLifecycleService orderLifecycleService) {
        this.orderLifecycleService = orderLifecycleService;
    }

    @Operation(summary = "Mark order paid (mock)",
            description = "Simulates a successful payment. Only mapped when pegasus.payment.mock-enabled is true.")
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
