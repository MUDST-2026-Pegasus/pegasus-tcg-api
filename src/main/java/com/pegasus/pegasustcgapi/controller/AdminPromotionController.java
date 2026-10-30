package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.AdminPromotionRequest;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.AdminPromotion;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.AdminPromotionPage;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.Redemption;
import com.pegasus.pegasustcgapi.dto.AdminPromotionViews.Summary;
import com.pegasus.pegasustcgapi.dto.PromotionActionRequests;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.model.PromotionStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AdminPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Promotions (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/promotions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPromotionController {

    private final AdminPromotionService promotions;

    public AdminPromotionController(AdminPromotionService promotions) {
        this.promotions = promotions;
    }

    @Operation(summary = "List discount codes",
            description = "Pegasus and shop codes, newest first. q matches the code, the name or the shop. "
                    + "statusCounts covers every status under the same owner and q, for the tabs.")
    @GetMapping
    public ApiResult<AdminPromotionPage> list(
            @RequestParam(required = false) OwnerType owner,
            @RequestParam(required = false) PromotionStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(promotions.list(owner, status, q, page, size));
    }

    @Operation(summary = "Promotion KPIs over the last 30 days")
    @GetMapping("/summary")
    public ApiResult<Summary> summary() {
        return ApiResult.success(promotions.summary());
    }

    @Operation(summary = "Get a discount code")
    @GetMapping("/{id}")
    public ApiResult<AdminPromotion> get(@PathVariable long id) {
        return ApiResult.success(promotions.get(id));
    }

    @Operation(summary = "Create a Pegasus discount code",
            description = "409 PROMO_CODE_TAKEN when any Pegasus or shop code already uses it.")
    @PostMapping
    public ResponseEntity<ApiResult<AdminPromotion>> create(@Valid @RequestBody AdminPromotionRequest request,
            AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(promotions.create(request, principal.userId())));
    }

    @Operation(summary = "Replace the home-page code strip",
            description = "Up to 8 public, claimable Pegasus codes that have not ended, in display order. "
                    + "Featured codes left out are taken off the home page.")
    @PutMapping("/home-order")
    public ApiResult<List<AdminPromotion>> homeOrder(
            @Valid @RequestBody PromotionActionRequests.HomeOrder request, AuthPrincipal principal) {
        return ApiResult.success(promotions.replaceHomeOrder(request.promotionIds(), principal.userId()));
    }

    @Operation(summary = "Update a Pegasus discount code",
            description = "Send the whole form. Once used, only name, description, a later end, a higher "
                    + "usage limit and the claim settings may change (else 409 PROMO_IN_USE). "
                    + "Shop codes are 403.")
    @PutMapping("/{id}")
    public ApiResult<AdminPromotion> update(@PathVariable long id,
            @Valid @RequestBody AdminPromotionRequest request, AuthPrincipal principal) {
        return ApiResult.success(promotions.update(id, request, principal.userId()));
    }

    @Operation(summary = "Delete a Pegasus discount code",
            description = "Only a code no order has held and nobody has claimed; else 409 PROMO_IN_USE.")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, AuthPrincipal principal) {
        promotions.delete(id, principal.userId());
        return ApiResult.success("Discount code deleted", null);
    }

    @Operation(summary = "Pause a Pegasus or shop code",
            description = "A reason is required for a shop's code; the shop sees it.")
    @PostMapping("/{id}/pause")
    public ApiResult<AdminPromotion> pause(@PathVariable long id,
            @Valid @RequestBody PromotionActionRequests.Pause request, AuthPrincipal principal) {
        return ApiResult.success(promotions.pause(id, request, principal.userId()));
    }

    @Operation(summary = "Resume a paused code")
    @PostMapping("/{id}/resume")
    public ApiResult<AdminPromotion> resume(@PathVariable long id,
            @Valid @RequestBody PromotionActionRequests.Resume request, AuthPrincipal principal) {
        return ApiResult.success(promotions.resume(id, request, principal.userId()));
    }

    @Operation(summary = "Orders that used a code", description = "Newest first.")
    @GetMapping("/{id}/redemptions")
    public ApiResult<PageResponse<Redemption>> redemptions(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(promotions.redemptions(id, page, size));
    }
}
