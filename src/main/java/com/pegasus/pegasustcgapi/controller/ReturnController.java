package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.FinanceViews.ReturnRequest;
import com.pegasus.pegasustcgapi.dto.SellerReturnRequestResponse;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.ReturnService;
import com.pegasus.pegasustcgapi.service.ReturnService.Item;
import com.pegasus.pegasustcgapi.service.ReturnService.EvidenceLink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@Tag(name = "Returns")
@SecurityRequirement(name = "BearerAuth")
@RestController
public class ReturnController {

    private final ReturnService returns;
    private final ObjectMapper json;

    public ReturnController(ReturnService returns, ObjectMapper json) {
        this.returns = returns;
        this.json = json;
    }

    @Operation(summary = "My return requests for a seller order")
    @GetMapping(ApiPaths.ORDERS + "/{sellerOrderId}/returns")
    public ApiResult<List<ReturnRequest>> mine(
            @Parameter(description = "seller_order.id (from sellerOrders[].id in GET /orders/{id}) only; does not accept sales_order.id")
            @PathVariable long sellerOrderId, AuthPrincipal principal) {
        return ApiResult.success(returns.buyerReturns(principal.userId(), sellerOrderId).stream()
                .map(row -> ReturnRequest.from(row, json)).toList());
    }

    @Operation(summary = "Request a return before escrow release")
    @PostMapping(ApiPaths.ORDERS + "/{sellerOrderId}/returns")
    public ApiResult<ReturnRequest> request(
            @Parameter(description = "seller_order.id (from sellerOrders[].id in GET /orders/{id}) only; does not accept sales_order.id")
            @PathVariable long sellerOrderId,
            @Valid @RequestBody Create body, AuthPrincipal principal) {
        return ApiResult.success(ReturnRequest.from(returns.request(principal.userId(), sellerOrderId,
                body.reasonCode(), body.description(), body.evidenceKeys(), body.items()), json));
    }

    @PostMapping(ApiPaths.ORDERS + "/{sellerOrderId}/returns/{returnId}/ship")
    public ApiResult<ReturnRequest> ship(
            @Parameter(description = "seller_order.id (from sellerOrders[].id in GET /orders/{id}) only; does not accept sales_order.id")
            @PathVariable long sellerOrderId,
            @PathVariable long returnId, @Valid @RequestBody Ship body, AuthPrincipal principal) {
        return ApiResult.success(ReturnRequest.from(returns.ship(principal.userId(), sellerOrderId,
                returnId, body.trackingNumber()), json));
    }

    @Operation(summary = "Returns for the signed-in seller's orders")
    @PreAuthorize("hasRole('SELLER')")
    @GetMapping(ApiPaths.SELLERS_ME + "/returns")
    public ApiResult<List<SellerReturnRequestResponse>> sellerQueue(
            @Parameter(description = "Optional status filter (e.g., REQUESTED, IN_TRANSIT)")
            @RequestParam(required = false) String status,
            AuthPrincipal principal) {
        return ApiResult.success(returns.sellerReturns(principal.userId(), status));
    }

    @Operation(summary = "Short-lived evidence links for a return owned by this seller")
    @PreAuthorize("hasRole('SELLER')")
    @GetMapping(ApiPaths.SELLERS_ME + "/returns/{id}/evidence")
    public ApiResult<List<EvidenceLink>> sellerEvidence(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(returns.sellerEvidence(principal.userId(), id));
    }

    @PostMapping(ApiPaths.SELLERS_ME + "/returns/{id}/approve")
    public ApiResult<ReturnRequest> approve(@PathVariable long id, @Valid @RequestBody Approve body,
            AuthPrincipal principal) {
        return ApiResult.success(ReturnRequest.from(returns.approve(principal.userId(), id, body.resolution(),
                body.refundAmount(), body.requireShipBack()), json));
    }

    @PostMapping(ApiPaths.SELLERS_ME + "/returns/{id}/reject")
    public ApiResult<ReturnRequest> reject(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(ReturnRequest.from(returns.reject(principal.userId(), id), json));
    }

    @PostMapping(ApiPaths.SELLERS_ME + "/returns/{id}/receive")
    public ApiResult<ReturnRequest> receive(@PathVariable long id,
            @Valid @RequestBody Receive body, AuthPrincipal principal) {
        return ApiResult.success(ReturnRequest.from(returns.receive(principal.userId(), id, body.restock()), json));
    }

    public record Create(@NotBlank String reasonCode, String description,
            @NotNull List<String> evidenceKeys, @NotEmpty List<Item> items) {
    }

    public record Ship(@NotBlank String trackingNumber) {
    }

    public record Approve(@NotBlank String resolution,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal refundAmount,
            @NotNull Boolean requireShipBack) {
    }

    public record Receive(@NotNull Boolean restock) {
    }
}
