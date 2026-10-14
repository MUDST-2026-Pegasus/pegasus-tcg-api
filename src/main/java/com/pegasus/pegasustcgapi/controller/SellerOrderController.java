package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.SellerCancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.SellerOrderViews.Summary;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.SellerOrderDeskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seller's order desk: their own sub-orders, and taking one from paid to posted.
 */
@Tag(name = "Seller Orders", description = "Seller order fulfillment and shipments")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME_ORDERS)
public class SellerOrderController {

    private final SellerOrderDeskService desk;

    public SellerOrderController(SellerOrderDeskService desk) {
        this.desk = desk;
    }

    @Operation(summary = "List seller orders",
            description = "A page of the authenticated seller's sub-orders with the buyer, address and "
                    + "ship-by date. status takes several values, repeated or comma-separated (PAID,PREPARING "
                    + "is what needs packing); q matches the order numbers and the buyer's username or "
                    + "display name. sort is one of NEWEST, OLDEST, PAID_OLDEST, SHIPPED_NEWEST, "
                    + "COMPLETED_NEWEST, TOTAL_DESC.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Orders retrieved"),
            @ApiResponse(responseCode = "400", description = "Unknown status or sort")
    })
    @GetMapping
    public ApiResult<PageResponse<Summary>> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(desk.list(principal, status, q, sort, page, size));
    }

    @Operation(summary = "Count seller orders by status",
            description = "How many of the seller's sub-orders are in each status; statuses with none "
                    + "are left out.")
    @ApiResponse(responseCode = "200", description = "Counts retrieved")
    @GetMapping("/counts")
    public ApiResult<Map<String, Long>> counts(AuthPrincipal principal) {
        return ApiResult.success(desk.counts(principal));
    }

    @Operation(summary = "Get seller order by ID",
            description = "One sub-order with its items, shipments, history, buyer, address, latest "
                    + "payment and the cost of the cards.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order retrieved"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{id}")
    public ApiResult<Detail> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(desk.detail(principal, id));
    }

    @Operation(summary = "Accept order", description = "Takes a PAID sub-order on: PREPARING, with accepted_at set.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order accepted"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order is not PAID")
    })
    @PostMapping("/{id}/accept")
    public ApiResult<Detail> accept(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success("Order accepted", desk.accept(principal, id));
    }

    @Operation(summary = "Ship order", description = "Marks a seller order as shipped, creates shipment tracking, commits inventory sale, and starts escrow auto-completion timer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order shipped successfully"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order status transition forbidden")
    })
    @PostMapping("/{id}/ship")
    public ApiResult<Detail> ship(
            @PathVariable long id,
            @Valid @RequestBody ShipOrderRequest request,
            AuthPrincipal principal) {
        return ApiResult.success("Order shipped successfully", desk.ship(principal, id, request));
    }

    @Operation(summary = "Cancel order",
            description = "Calls off a sub-order that has not shipped. The cards go back on sale and any "
                    + "payment is refunded; the reason is shown to the buyer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled"),
            @ApiResponse(responseCode = "400", description = "Reason missing or too long"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Already shipped, or a transfer is under review")
    })
    @PostMapping("/{id}/cancel")
    public ApiResult<Detail> cancel(
            @PathVariable long id,
            @Valid @RequestBody SellerCancelOrderRequest request,
            AuthPrincipal principal) {
        return ApiResult.success("Order cancelled", desk.cancel(principal, id, request.reason()));
    }
}
