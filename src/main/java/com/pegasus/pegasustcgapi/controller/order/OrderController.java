package com.pegasus.pegasustcgapi.controller.order;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.order.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.order.OrderDetailsResponse;
import com.pegasus.pegasustcgapi.dto.order.ReorderItemsResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.order.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.order.pdf.OrderSummaryPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final OrderSummaryPdfService orderSummaryPdfs;

    public OrderController(OrderLifecycleService orderLifecycleService, OrderSummaryPdfService orderSummaryPdfs) {
        this.orderLifecycleService = orderLifecycleService;
        this.orderSummaryPdfs = orderSummaryPdfs;
    }

    @Operation(summary = "List buyer orders",
            description = "Retrieves a page of sales orders placed by the authenticated buyer, newest first.")
    @ApiResponse(responseCode = "200", description = "Orders retrieved")
    @GetMapping
    public ApiResult<PageResponse<OrderDetailsResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(orderLifecycleService.listBuyerOrders(principal, page, size));
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

    @Operation(summary = "Download buyer order summary PDF",
            description = "Downloads the authenticated buyer's purchase summary for this order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF downloaded",
                    content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")),
                    headers = @Header(name = "Content-Disposition",
                            description = "Attachment filename order-summary-{orderNumber}.pdf",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping(value = "/{id}/summary.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadSummary(@PathVariable long id, AuthPrincipal principal) {
        return OrderSummaryPdfHttpResponse.from(orderSummaryPdfs.buyer(principal, id));
    }

    @Operation(summary = "Get reorder items",
            description = "Retrieves purchased items from a completed order with product metadata for PDP navigation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reorder items retrieved"),
            @ApiResponse(responseCode = "400", description = "Order is not completed"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    @GetMapping("/{orderId}/reorder-items")
    public ApiResult<ReorderItemsResponse> getReorderItems(
            @PathVariable long orderId,
            AuthPrincipal principal) {
        return ApiResult.success(orderLifecycleService.getReorderItems(orderId, principal.userId()));
    }

    @Operation(summary = "Cancel order", description = "Cancels an order within the cancellation window before shipping.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled successfully"),
            @ApiResponse(responseCode = "400", description = "Cancellation reason is longer than the field allows"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Order status transition forbidden or cancellation window expired")
    })
    @PostMapping("/{id}/cancel")
    public ApiResult<OrderDetailsResponse> cancel(
            @PathVariable long id,
            @Valid @RequestBody(required = false) CancelOrderRequest request,
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
}
