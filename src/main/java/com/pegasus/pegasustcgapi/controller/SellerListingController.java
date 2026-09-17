package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResponse;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ListingDetailsRequest;
import com.pegasus.pegasustcgapi.dto.ListingPriceRequest;
import com.pegasus.pegasustcgapi.dto.ListingRequest;
import com.pegasus.pegasustcgapi.dto.ListingStatusRequest;
import com.pegasus.pegasustcgapi.dto.ListingUnitResponse;
import com.pegasus.pegasustcgapi.dto.NewUnitsRequest;
import com.pegasus.pegasustcgapi.dto.PriceChangeResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingSummaryResponse;
import com.pegasus.pegasustcgapi.dto.UnitUpdateRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.ListingUnitStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.ListingService;
import com.pegasus.pegasustcgapi.service.ListingUnitService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in seller's own listings and the cards on them.
 *
 * <p>No role check on the class, as with the rest of /sellers/me: the service asks
 * the seller module, which reads the profile's status from the database rather
 * than trusting a token that may be a TTL out of date. Reading needs a seller
 * profile; changing anything needs it VERIFIED [RQ-1].
 */
@RestController
@RequestMapping(ApiPaths.SELLERS_ME + "/listings")
public class SellerListingController {

    private final ListingService listings;
    private final ListingUnitService units;

    public SellerListingController(ListingService listings, ListingUnitService units) {
        this.listings = listings;
        this.units = units;
    }

    // ---------- listings ----------

    @GetMapping
    public ApiResponse<PageResponse<SellerListingSummaryResponse>> list(
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {

        return ApiResponse.success(listings.mine(principal.userId(), status, variantId, condition, page, size));
    }

    /** A DRAFT with no cards. A second listing of the same card and condition is fine; the message says so. */
    @PostMapping
    public ResponseEntity<ApiResponse<SellerListingResponse>> create(
            @Valid @RequestBody ListingRequest request, AuthPrincipal principal) {

        SellerListingResponse created = listings.create(principal.userId(), request.market(), request.details(),
                request.pricing(), request.photos());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created.message("Listing created"), created));
    }

    @GetMapping("/{listingId}")
    public ApiResponse<SellerListingResponse> get(@PathVariable long listingId, AuthPrincipal principal) {
        return ApiResponse.success(listings.get(principal.userId(), listingId));
    }

    @PutMapping("/{listingId}")
    public ApiResponse<SellerListingResponse> update(
            @PathVariable long listingId,
            @Valid @RequestBody ListingDetailsRequest request,
            AuthPrincipal principal) {

        SellerListingResponse updated = listings.updateDetails(
                principal.userId(), listingId, request.details(), request.photos());
        return ApiResponse.success(updated.message("Listing updated"), updated);
    }

    /** One price for every card on the listing. */
    @PatchMapping("/{listingId}/price")
    public ApiResponse<SellerListingResponse> changePrice(
            @PathVariable long listingId,
            @Valid @RequestBody ListingPriceRequest request,
            AuthPrincipal principal) {

        SellerListingResponse updated = listings.changePrice(principal.userId(), listingId, request.toChange());
        return ApiResponse.success(updated.message("Price updated"), updated);
    }

    @PatchMapping("/{listingId}/status")
    public ApiResponse<SellerListingResponse> changeStatus(
            @PathVariable long listingId,
            @Valid @RequestBody ListingStatusRequest request,
            AuthPrincipal principal) {

        SellerListingResponse updated = listings.changeStatus(principal.userId(), listingId, request.status());
        return ApiResponse.success(updated.message("Listing is now " + updated.status()), updated);
    }

    /** Refused while an open order holds cards on it. Cards on sale go back into the seller's hands. */
    @DeleteMapping("/{listingId}")
    public ApiResponse<Void> delete(@PathVariable long listingId, AuthPrincipal principal) {
        listings.delete(principal.userId(), listingId);
        return ApiResponse.success("Listing deleted", null);
    }

    @GetMapping("/{listingId}/price-history")
    public ApiResponse<PageResponse<PriceChangeResponse>> priceHistory(
            @PathVariable long listingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {

        return ApiResponse.success(listings.priceHistory(principal.userId(), listingId, page, size));
    }

    // ---------- the cards on a listing ----------

    @GetMapping("/{listingId}/units")
    public ApiResponse<PageResponse<ListingUnitResponse>> units(
            @PathVariable long listingId,
            @RequestParam(required = false) ListingUnitStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {

        return ApiResponse.success(units.onListing(principal.userId(), listingId, status, page, size));
    }

    /** New cards straight onto this listing, each with its own UUID and ledger line. */
    @PostMapping("/{listingId}/units")
    public ResponseEntity<ApiResponse<List<ListingUnitResponse>>> addUnits(
            @PathVariable long listingId,
            @Valid @RequestBody NewUnitsRequest request,
            AuthPrincipal principal) {

        List<ListingUnitResponse> created = units.stockIn(principal.userId(), request.toStockIn(listingId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created.size() + " card(s) added", created));
    }

    @GetMapping("/{listingId}/units/{unitUid}")
    public ApiResponse<ListingUnitResponse> unit(
            @PathVariable long listingId, @PathVariable UUID unitUid, AuthPrincipal principal) {

        return ApiResponse.success(units.getOnListing(principal.userId(), listingId, unitUid));
    }

    @PutMapping("/{listingId}/units/{unitUid}")
    public ApiResponse<ListingUnitResponse> updateUnit(
            @PathVariable long listingId,
            @PathVariable UUID unitUid,
            @Valid @RequestBody UnitUpdateRequest request,
            AuthPrincipal principal) {

        return ApiResponse.success("Card updated",
                units.updateNotes(principal.userId(), listingId, unitUid, request.toNotes()));
    }

    /** Off this listing and back into the seller's hands; the card itself stays in stock. */
    @DeleteMapping("/{listingId}/units/{unitUid}")
    public ApiResponse<Void> removeUnit(
            @PathVariable long listingId, @PathVariable UUID unitUid, AuthPrincipal principal) {

        units.removeFromListing(principal.userId(), listingId, unitUid);
        return ApiResponse.success("Card taken off the listing", null);
    }
}
