package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.SellerPromotionRequest;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.Redemption;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.SellerPromotion;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.SellerPromotionPage;
import com.pegasus.pegasustcgapi.dto.SellerPromotionViews.Summary;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.SellerPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in shop's own discount codes. Another shop's code, or Pegasus's, is 404. */
@Tag(name = "Seller Promotions", description = "A shop's own discount codes, paid for by the shop")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME + "/promotions")
public class SellerPromotionController {

    private final SellerPromotionService promotions;

    public SellerPromotionController(SellerPromotionService promotions) {
        this.promotions = promotions;
    }

    @Operation(summary = "List the shop's discount codes",
            description = "Newest first. statusCounts covers every status, for the tabs.")
    @GetMapping
    public ApiResult<SellerPromotionPage> list(
            @RequestParam(required = false) PromotionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(promotions.list(principal.userId(), status, page, size));
    }

    @Operation(summary = "The shop's promotion KPIs over the last 30 days")
    @GetMapping("/summary")
    public ApiResult<Summary> summary(AuthPrincipal principal) {
        return ApiResult.success(promotions.summary(principal.userId()));
    }

    @Operation(summary = "Get one of the shop's discount codes")
    @GetMapping("/{id}")
    public ApiResult<SellerPromotion> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(promotions.get(principal.userId(), id));
    }

    @Operation(summary = "Create a shop discount code",
            description = "PERCENT or FIXED over the whole shop. The shop must be VERIFIED "
                    + "(403 SELLER_NOT_VERIFIED). 409 PROMO_CODE_TAKEN when any code already uses it.")
    @PostMapping
    public ResponseEntity<ApiResult<SellerPromotion>> create(@Valid @RequestBody SellerPromotionRequest request,
            AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(promotions.create(principal.userId(), request)));
    }

    @Operation(summary = "Update a shop discount code",
            description = "Send the whole form. Once used, only name, description, a later end and a higher "
                    + "usage limit may change; once claimed, the claim limit can only go up (else 409 PROMO_IN_USE).")
    @PutMapping("/{id}")
    public ApiResult<SellerPromotion> update(@PathVariable long id,
            @Valid @RequestBody SellerPromotionRequest request, AuthPrincipal principal) {
        return ApiResult.success(promotions.update(principal.userId(), id, request));
    }

    @Operation(summary = "Delete a shop discount code",
            description = "Only a code no order has held and nobody has claimed; else 409 PROMO_IN_USE.")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, AuthPrincipal principal) {
        promotions.delete(principal.userId(), id);
        return ApiResult.success("Discount code deleted", null);
    }

    @Operation(summary = "Pause a shop discount code")
    @PostMapping("/{id}/pause")
    public ApiResult<SellerPromotion> pause(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(promotions.pause(principal.userId(), id));
    }

    @Operation(summary = "Resume a shop discount code",
            description = "409 PROMO_PAUSED when Pegasus paused it; only Pegasus can resume it then.")
    @PostMapping("/{id}/resume")
    public ApiResult<SellerPromotion> resume(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(promotions.resume(principal.userId(), id));
    }

    @Operation(summary = "Orders that used a shop code",
            description = "Newest first; the buyer is shown by display name only.")
    @GetMapping("/{id}/redemptions")
    public ApiResult<PageResponse<Redemption>> redemptions(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(promotions.redemptions(principal.userId(), id, page, size));
    }
}
