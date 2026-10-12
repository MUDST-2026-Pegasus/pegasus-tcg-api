package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.CatalogImageResponse;
import com.pegasus.pegasustcgapi.dto.ProductDetailResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.TrendingProductResponse;
import com.pegasus.pegasustcgapi.dto.VariantLookupResponse;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.service.CatalogImageService;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.service.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.CatalogVariantService;
import com.pegasus.pegasustcgapi.service.ProductBrowse;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the catalogue. Open to anyone, like the rest of it.
 *
 * <p>A product is addressable by id or by slug, because a URL carries the slug
 * and an admin screen carries the id, and neither should have to translate.
 *
 * <p>Retired products and printings are not found here, by id or slug any more
 * than by browsing; admins read them through {@link AdminCatalogProductController}.
 */
@RestController
@RequestMapping(ApiPaths.CATALOG)
public class CatalogProductController {

    private final CatalogProductService products;
    private final CatalogVariantService variants;
    private final CatalogImageService images;
    private final CatalogSearchService search;

    public CatalogProductController(CatalogProductService products, CatalogVariantService variants,
            CatalogImageService images, CatalogSearchService search) {

        this.products = products;
        this.variants = variants;
        this.images = images;
        this.search = search;
    }

    /**
     * Browsing and searching.
     *
     * <p>Attribute filters arrive as {@code attr.<key>=<value>} — {@code attr.hp=200},
     * {@code attr.card_type=Lightning} — which is why the whole parameter map is
     * taken as well as the named ones: which keys exist depends on the game, and
     * only its registry knows them.
     *
     * @param gameId     repeat for several games; attribute filters need exactly
     *                   one, to know what {@code attr.hp} means
     * @param categoryId also finds what is filed in its child categories
     * @param q          matched against names and the card number
     * @param sort       name (default), newest, cardNumber, price_asc, price_desc or popular
     * @param inStock    true for only the cards someone is selling right now
     * @param condition  repeat for several; only cards on sale in one of them, priced by those listings
     * @param minPrice   THB, against the cheapest matching listing on sale
     * @param maxPrice   THB, same
     */
    @GetMapping("/products")
    public ApiResult<PageResponse<ProductSummaryResponse>> browse(
            @RequestParam(required = false) List<Short> gameId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer cardSetId,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(required = false) List<CardCondition> condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> allParameters) {

        return ApiResult.success(search.search(new ProductBrowse(
                gameId == null ? null : new HashSet<>(gameId), categoryId, cardSetId, productType, q,
                allParameters, sort, true, inStock,
                condition == null ? null : new HashSet<>(condition),
                minPrice, maxPrice, page, size)));
    }

    /**
     * The cards that have been selling: most sold over the last {@code days} first,
     * then the ones more sellers are offering. Only cards on sale now are ranked.
     *
     * @param gameId optional; left out, every game is ranked together
     * @param days   1–365, default 30
     * @param limit  1–50, default 10
     */
    @GetMapping("/trending")
    public ApiResult<List<TrendingProductResponse>> trending(
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Integer limit) {

        return ApiResult.success(search.trending(gameId, days, limit));
    }

    /**
     * Finds one printing by the code on it — a SKU quoted between people, or a
     * barcode from a scanner.
     *
     * <p>Here rather than behind the seller-side endpoints because the codes
     * belong to the catalogue: a seller about to list a card needs to resolve the
     * code to the variant everyone else's listings already point at [RQ-5].
     */
    @GetMapping("/variants/by-code/{code}")
    public ApiResult<VariantLookupResponse> variantByCode(@PathVariable String code) {
        return ApiResult.success(variants.lookupByCode(code));
    }

    /** The card page: the concept, its printings and its art in one read. */
    @GetMapping("/products/{idOrSlug}")
    public ApiResult<ProductDetailResponse> product(@PathVariable String idOrSlug) {
        CatalogProduct product = products.requireActiveByIdOrSlug(idOrSlug);

        return ApiResult.success(new ProductDetailResponse(
                product,
                variants.listOfProduct(product.id(), false),
                images.listOfProduct(product.id(), false)));
    }

    @GetMapping("/products/{productId}/variants")
    public ApiResult<List<CatalogVariant>> variants(@PathVariable long productId) {
        return ApiResult.success(variants.listOfProduct(productId, false));
    }

    /** What a listing, a wishlist entry and a market statistic all point at [RQ-5]. */
    @GetMapping("/variants/{variantId}")
    public ApiResult<CatalogVariant> variant(@PathVariable long variantId) {
        return ApiResult.success(variants.requireActive(variantId));
    }

    @GetMapping("/products/{productId}/images")
    public ApiResult<List<CatalogImageResponse>> images(@PathVariable long productId) {
        return ApiResult.success(images.listOfProduct(productId, false));
    }
}
