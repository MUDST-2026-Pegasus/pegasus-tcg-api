package com.pegasus.pegasustcgapi.controller.catalog;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.CatalogImageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductDetailResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductDetailResponse.Auctions;
import com.pegasus.pegasustcgapi.dto.pricing.ProductMarketResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchFacetsResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchShopResponse;
import com.pegasus.pegasustcgapi.dto.catalog.SearchSuggestResponse;
import com.pegasus.pegasustcgapi.security.ClientAddress;
import com.pegasus.pegasustcgapi.service.catalog.SearchSuggestService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.pegasus.pegasustcgapi.dto.catalog.TrendingProductResponse;
import com.pegasus.pegasustcgapi.dto.catalog.VariantLookupResponse;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import com.pegasus.pegasustcgapi.model.catalog.ProductType;
import com.pegasus.pegasustcgapi.service.auction.AuctionBrowseService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogImageService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogProductService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogVariantService;
import com.pegasus.pegasustcgapi.service.catalog.ProductBrowse;
import com.pegasus.pegasustcgapi.service.pricing.ProductMarketService;
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
    private final ProductMarketService market;
    private final AuctionBrowseService auctions;
    private final SearchSuggestService suggestions;

    public CatalogProductController(CatalogProductService products, CatalogVariantService variants,
            CatalogImageService images, CatalogSearchService search, ProductMarketService market,
            AuctionBrowseService auctions, SearchSuggestService suggestions) {

        this.suggestions = suggestions;
        this.products = products;
        this.variants = variants;
        this.images = images;
        this.search = search;
        this.market = market;
        this.auctions = auctions;
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
     * @param onSale     true for only cards with a listing in a running sale
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
            @RequestParam(defaultValue = "false") boolean onSale,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> allParameters,
            HttpServletRequest request) {

        PageResponse<ProductSummaryResponse> found = search.search(toBrowse(gameId, categoryId, cardSetId,
                productType, q, sort, inStock, condition, minPrice, maxPrice, onSale, page, size, allParameters));
        if (q != null && !q.isBlank() && page == 0) {
            suggestions.record(q, found, ClientAddress.of(request) + "|" + request.getHeader("User-Agent"));
        }
        return ApiResult.success(found);
    }

    /** The search box dropdown; an empty {@code q} gives empty lists (show trending instead). */
    @GetMapping("/search/suggest")
    public ResponseEntity<ApiResult<SearchSuggestResponse>> suggest(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Short gameId) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(ApiResult.success(suggestions.suggest(q, gameId)));
    }

    /** What people searched most this week (at least three different people, something found). */
    @GetMapping("/search/trending")
    public ApiResult<List<SearchSuggestResponse.Trending>> trendingSearches(
            @RequestParam(required = false) Integer limit) {
        return ApiResult.success(suggestions.trending(limit));
    }

    /** Sidebar counts and tab totals for the same parameters as {@code /products}. */
    @GetMapping("/products/facets")
    public ApiResult<SearchFacetsResponse> facets(
            @RequestParam(required = false) List<Short> gameId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer cardSetId,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(required = false) List<CardCondition> condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean onSale,
            @RequestParam Map<String, String> allParameters) {

        return ApiResult.success(search.facets(toBrowse(gameId, categoryId, cardSetId, productType, q, null, inStock,
                condition, minPrice, maxPrice, onSale, 0, 1, allParameters)));
    }

    /**
     * Shops selling something a search for {@code q} finds.
     *
     * @param minRating e.g. 4.0 or 4.5
     * @param sort      MATCHES (default) or RATING
     */
    @GetMapping("/shops")
    public ApiResult<PageResponse<SearchShopResponse>> shops(
            @RequestParam(required = false) List<Short> gameId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(search.shops(toBrowse(gameId, null, null, null, q, null, false, null, null, null,
                false, page, size, Map.of()), minRating, sort));
    }

    private static ProductBrowse toBrowse(List<Short> gameId, Integer categoryId, Integer cardSetId,
            ProductType productType, String q, String sort, boolean inStock, List<CardCondition> condition,
            BigDecimal minPrice, BigDecimal maxPrice, boolean onSale, int page, int size,
            Map<String, String> allParameters) {
        return new ProductBrowse(gameId == null ? null : new HashSet<>(gameId), categoryId, cardSetId, productType,
                q, allParameters, sort, true, inStock, condition == null ? null : new HashSet<>(condition),
                minPrice, maxPrice, page, size, null, onSale);
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

        var auctionStats = auctions.productStats(product.id());
        return ApiResult.success(new ProductDetailResponse(
                product,
                variants.listOfProduct(product.id(), false),
                images.listOfProduct(product.id(), false),
                new Auctions(auctionStats.openCount(), auctionStats.lowestCurrentPrice(),
                        auctionStats.nearestEndAt())));
    }

    /** Separate from product detail so existing clients and browse pages do not fetch market history unnecessarily. */
    @GetMapping("/products/{idOrSlug}/market")
    public ApiResult<List<ProductMarketResponse>> market(@PathVariable String idOrSlug) {
        CatalogProduct product = products.requireActiveByIdOrSlug(idOrSlug);
        return ApiResult.success(market.ofProduct(product.id()));
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
