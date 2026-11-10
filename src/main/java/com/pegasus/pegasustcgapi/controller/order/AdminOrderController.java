package com.pegasus.pegasustcgapi.controller.order;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.csv.CsvExport;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Board;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Detail;
import com.pegasus.pegasustcgapi.dto.order.AdminOrderViews.Summary;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.order.AdminOrderService;
import com.pegasus.pegasustcgapi.service.order.pdf.OrderSummaryPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final OrderSummaryPdfService orderSummaryPdfs;

    public AdminOrderController(AdminOrderService orders, OrderSummaryPdfService orderSummaryPdfs) {
        this.orders = orders;
        this.orderSummaryPdfs = orderSummaryPdfs;
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

    @Operation(summary = "Export sub-orders as CSV",
            description = "One row per sub-order placed in [from, to), newest first, with the same "
                    + "status and q filters as the search. UTF-8 with a BOM; times are Asia/Bangkok. "
                    + "Columns: seller_order_number, order_number, status, seller, buyer, item_count, "
                    + "grand_total, created_at, paid_at, shipped_at, completed_at, cancelled_at, "
                    + "tracking_number. At most 20,000 rows.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSV downloaded",
                    content = @Content(mediaType = "text/csv",
                            schema = @Schema(type = "string", format = "binary")),
                    headers = @Header(name = "Content-Disposition",
                            description = "Attachment filename admin-orders-{from}-{to}.csv (Bangkok dates)",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400",
                    description = "from or to missing, to not after from, unknown status, "
                            + "or EXPORT_TOO_LARGE above 20,000 rows"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin")
    })
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String q,
            AuthPrincipal principal) {

        CsvExport file = orders.export(status, q, from, to, principal.userId());
        return file.toResponse();
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

    @Operation(summary = "Download admin order summary PDF",
            description = "Downloads the full administrative summary for a seller sub-order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF downloaded",
                    content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")),
                    headers = @Header(name = "Content-Disposition",
                            description = "Attachment filename order-summary-{orderNumber}.pdf",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping(value = "/{sellerOrderId}/summary.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadSummary(@PathVariable long sellerOrderId) {
        return OrderSummaryPdfHttpResponse.from(orderSummaryPdfs.admin(sellerOrderId));
    }
}
