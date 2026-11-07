package com.pegasus.pegasustcgapi.controller.listing;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.listing.AdminCardListingResponse;
import com.pegasus.pegasustcgapi.dto.listing.ListingBlockRequest;
import com.pegasus.pegasustcgapi.model.listing.ListingStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.listing.AdminCardListingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Which shops sell a card, and suspending one listing [TCG-730]. */
@Tag(name = "Listings (Admin)", description = "See who sells a card, and suspend or restore a listing")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminCardListingController {

    private final AdminCardListingService listings;

    public AdminCardListingController(AdminCardListingService listings) {
        this.listings = listings;
    }

    @Operation(summary = "Every shop's listings of a card",
            description = "q matches the shop's names or the listing number. Without a status, drafts and closed "
                    + "listings are left out. The summary covers the whole card, whatever the filters.")
    @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    @GetMapping(ApiPaths.ADMIN + "/catalog/products/{productId}/listings")
    public ApiResult<AdminCardListingResponse.AdminCardListingPage> ofProduct(
            @PathVariable long productId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(listings.ofProduct(productId, q, variantId, status, page, size));
    }

    @Operation(summary = "Suspend a listing",
            description = "Off the market at once; the seller cannot change or delete it and sees the reason. "
                    + "Cards held by open orders stay held and those orders carry on.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Blocked"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: reason is required"),
            @ApiResponse(responseCode = "404", description = "LISTING_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "LISTING_STATUS_TRANSITION: closed or already blocked")
    })
    @PostMapping(ApiPaths.ADMIN_LISTINGS + "/{id}/block")
    public ApiResult<AdminCardListingResponse> block(@PathVariable long id,
            @Valid @RequestBody ListingBlockRequest request, AuthPrincipal principal) {
        listings.block(id, principal.userId(), request.reason());
        return ApiResult.success(listings.one(id));
    }

    @Operation(summary = "Lift a listing's suspension",
            description = "Back to the status it had. One that was on the market goes back only if its shop is "
                    + "still verified, otherwise it waits PAUSED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Restored"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: reason is required"),
            @ApiResponse(responseCode = "404", description = "LISTING_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "LISTING_STATUS_TRANSITION: not blocked")
    })
    @PostMapping(ApiPaths.ADMIN_LISTINGS + "/{id}/unblock")
    public ApiResult<AdminCardListingResponse> unblock(@PathVariable long id,
            @Valid @RequestBody ListingBlockRequest request, AuthPrincipal principal) {
        listings.unblock(id, principal.userId(), request.reason());
        return ApiResult.success(listings.one(id));
    }
}
