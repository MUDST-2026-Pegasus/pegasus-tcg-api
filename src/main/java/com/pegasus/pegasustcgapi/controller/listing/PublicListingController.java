package com.pegasus.pegasustcgapi.controller.listing;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingDetailResponse;
import com.pegasus.pegasustcgapi.dto.listing.PublicListingResponse;
import com.pegasus.pegasustcgapi.dto.user.PublicProfileResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.service.listing.ListingBrowseService;
import com.pegasus.pegasustcgapi.service.listing.ListingBrowseService.SaleFilter;
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

    /**
     * The market and its search. Cheapest first by default; {@code sort} is price,
     * price_desc, newest, discount_desc or ending_soon. Prices, their filters and
     * their sorts are what a buyer pays now, sale included [TCG-679].
     *
     * @param onSale      true: only cards on sale; false: only cards off sale
     * @param saleId      only cards priced by this sale
     * @param minDiscount only cards at least this many percent off (1–99)
     */
    @GetMapping(ApiPaths.LISTINGS)
    public ApiResult<PageResponse<PublicListingResponse>> market(
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean onSale,
            @RequestParam(required = false) Long saleId,
            @RequestParam(required = false) BigDecimal minDiscount,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(browse.market(variantId, productId, gameId, condition, minPrice, maxPrice,
                new SaleFilter(onSale, saleId, minDiscount), sort, page, size));
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

    @GetMapping(ApiPaths.PROFILES + "/{username}")
    public ApiResult<PublicProfileResponse> profile(@PathVariable String username) {
        return ApiResult.success(browse.profile(username));
    }
}
