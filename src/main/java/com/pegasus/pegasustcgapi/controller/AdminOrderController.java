package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.AdminOrderViews.Board;
import com.pegasus.pegasustcgapi.dto.AdminOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.AdminOrderViews.Summary;
import com.pegasus.pegasustcgapi.service.AdminOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every seller's sub-orders, for the admin order overview. Read-only: the
 * transitions stay with the buyer, seller and finance endpoints that own them.
 */
@Tag(name = "Orders (Admin)", description = "Platform-wide order board, search and detail")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(ApiPaths.ADMIN + "/orders")
public class AdminOrderController {

    private final AdminOrderService orders;

    public AdminOrderController(AdminOrderService orders) {
        this.orders = orders;
    }

    @Operation(summary = "Order board",
            description = "Five columns — PENDING_PAYMENT, AWAITING_SHIPMENT, IN_TRANSIT, COMPLETED, "
                    + "DISPUTED — each with its full count and the first perColumn sub-orders, "
                    + "plus the open dispute and overdue payment counts.")
    @ApiResponse(responseCode = "200", description = "Board retrieved")
    @GetMapping("/board")
    public ApiResult<Board> board(
            @RequestParam(defaultValue = "" + AdminOrderService.DEFAULT_PER_COLUMN) int perColumn) {
        return ApiResult.success(orders.board(perColumn));
    }

    @Operation(summary = "Search sub-orders",
            description = "status may repeat or be comma-separated. q matches either order number "
                    + "and the buyer's or seller's username or display name. sort is one of NEWEST, "
                    + "OLDEST, PAID_OLDEST, SHIPPED_NEWEST, COMPLETED_NEWEST, TOTAL_DESC.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sub-orders retrieved"),
            @ApiResponse(responseCode = "400", description = "Unknown status or sort")
    })
    @GetMapping
    public ApiResult<PageResponse<Summary>> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long sellerProfileId,
            @RequestParam(required = false) Long buyerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(
                orders.list(status, q, sellerProfileId, buyerId, from, to, sort, page, size));
    }

    @Operation(summary = "Sub-order detail",
            description = "Items, money, shipments and status history, with the shipping address "
                    + "and the newest payment attempt of the parent order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sub-order retrieved"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{sellerOrderId}")
    public ApiResult<Detail> detail(@PathVariable long sellerOrderId) {
        return ApiResult.success(orders.detail(sellerOrderId));
    }
}
