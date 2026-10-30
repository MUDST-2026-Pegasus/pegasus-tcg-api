package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.ClaimResult;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.ClaimableCode;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Terms;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.Wallet;
import com.pegasus.pegasustcgapi.dto.BuyerPromotionViews.WalletTab;
import com.pegasus.pegasustcgapi.dto.ClaimPromotionRequests;
import com.pegasus.pegasustcgapi.model.Promotion.OwnerType;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.BuyerPromotionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Collecting discount codes from the home and store pages, and "My coupons" [TCG-576]. */
@Tag(name = "Promotions", description = "Collect discount codes and the buyer's coupon wallet")
@SecurityRequirement(name = "BearerAuth")
@RestController
public class BuyerPromotionController {

    /** A guest's strip is the same for everyone, so a minute of caching is safe. */
    private static final CacheControl GUEST_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final BuyerPromotionService promotions;

    public BuyerPromotionController(BuyerPromotionService promotions) {
        this.promotions = promotions;
    }

    @Operation(summary = "Pegasus codes on the home page",
            description = "Live featured Pegasus codes in the admin's order, at most 8. claimed is false for a "
                    + "guest; remainingClaims is null when unlimited and 0 once all copies are out.")
    @GetMapping(ApiPaths.PROMOTIONS + "/featured")
    public ResponseEntity<ApiResult<List<ClaimableCode>>> featured(Optional<AuthPrincipal> principal) {
        Long userId = principal.map(AuthPrincipal::userId).orElse(null);
        return ResponseEntity.ok()
                .cacheControl(userId == null ? GUEST_CACHE : CacheControl.noStore())
                .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
                .body(ApiResult.success(promotions.featured(userId)));
    }

    @Operation(summary = "A shop's codes on its store page",
            description = "The shop's live PUBLIC codes that can be collected. Empty for a profile that is not a shop.")
    @GetMapping(ApiPaths.PROFILES + "/{username}/promotions")
    public ApiResult<List<ClaimableCode>> storeCodes(@PathVariable String username,
            Optional<AuthPrincipal> principal) {
        return ApiResult.success(promotions.storeCodes(username, principal.map(AuthPrincipal::userId).orElse(null)));
    }

    @Operation(summary = "A code's full terms",
            description = "For the terms modal. A PRIVATE code is PROMO_NOT_FOUND unless the caller collected it.")
    @GetMapping(ApiPaths.PROMOTIONS + "/{code}/terms")
    public ApiResult<Terms> terms(@PathVariable String code, Optional<AuthPrincipal> principal) {
        return ApiResult.success(promotions.terms(code, principal.map(AuthPrincipal::userId).orElse(null)));
    }

    @Operation(summary = "Collect a code from the home or store strip",
            description = "Idempotent: collecting again returns the first claim with alreadyClaimed=true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Collected, or already collected"),
            @ApiResponse(responseCode = "400", description = "PROMO_EXPIRED or PROMO_NOT_STARTED"),
            @ApiResponse(responseCode = "401", description = "Sign-in required"),
            @ApiResponse(responseCode = "404", description = "PROMO_NOT_FOUND, including PRIVATE codes and codes "
                    + "that cannot be collected"),
            @ApiResponse(responseCode = "409", description = "PROMO_CLAIM_FULL, PROMO_PAUSED or PROMO_USED_UP")
    })
    @PostMapping(ApiPaths.PROMOTIONS + "/{id}/claim")
    public ApiResult<ClaimResult> claim(@PathVariable long id,
            @RequestBody(required = false) ClaimPromotionRequests.ById request,
            AuthPrincipal principal) {
        return ApiResult.success(promotions.claim(principal.userId(), id,
                request == null ? null : request.source()));
    }

    @Operation(summary = "Collect a typed code into My coupons",
            description = "Any code, PRIVATE ones included. Same rules and response as collecting from a strip.")
    @PostMapping(ApiPaths.ME_PROMOTIONS + "/claim-by-code")
    public ApiResult<ClaimResult> claimByCode(@Valid @RequestBody ClaimPromotionRequests.ByCode request,
            AuthPrincipal principal) {
        return ApiResult.success(promotions.claimByCode(principal.userId(), request.code()));
    }

    @Operation(summary = "My coupons",
            description = "AVAILABLE: collected, live or scheduled, with uses left. USED: on a paid order, with "
                    + "the orders. EXPIRED: collected, unused, and ended, paused or used up (ended ones for 30 "
                    + "days). counts has every tab's size under the same owner filter.")
    @GetMapping(ApiPaths.ME_PROMOTIONS)
    public ApiResult<Wallet> wallet(
            @RequestParam(defaultValue = "AVAILABLE") WalletTab tab,
            @RequestParam(required = false) OwnerType owner,
            AuthPrincipal principal) {
        return ApiResult.success(promotions.wallet(principal.userId(), tab, owner));
    }
}
