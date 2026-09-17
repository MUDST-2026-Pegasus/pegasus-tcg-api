package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.PublicListingDetailResponse;
import com.pegasus.pegasustcgapi.dto.PublicListingResponse;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.service.ListingBrowseService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The market, readable without an account: every listing on sale, a listing's
 * page, and a seller's storefront on their profile.
 */
@RestController
public class PublicListingController {

    private final ListingBrowseService browse;

    public PublicListingController(ListingBrowseService browse) {
        this.browse = browse;
    }

    /** Cheapest first by default; {@code sort} is price, price_desc or newest. */
    @GetMapping(ApiPaths.LISTINGS)
    public ApiResult<PageResponse<PublicListingResponse>> market(
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(browse.market(
                variantId, productId, gameId, condition, minPrice, maxPrice, sort, page, size));
    }

    @GetMapping(ApiPaths.LISTINGS + "/{listingId}")
    public ApiResult<PublicListingDetailResponse> get(@PathVariable long listingId) {
        return ApiResult.success(browse.get(listingId));
    }

    @GetMapping(ApiPaths.PROFILES + "/{username}/listings")
    public ApiResult<PageResponse<PublicListingResponse>> storefront(
            @PathVariable String username,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(browse.storefront(username, variantId, condition, sort, page, size));
    }
}
