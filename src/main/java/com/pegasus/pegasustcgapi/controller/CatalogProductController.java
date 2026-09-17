package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.CatalogImageResponse;
import com.pegasus.pegasustcgapi.dto.ProductDetailResponse;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.VariantLookupResponse;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.service.CatalogImageService;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.service.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.CatalogVariantService;
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
     * @param gameId worth sending: the browse index starts with it, and attribute
     *               filters need it to know what {@code attr.hp} means
     */
    @GetMapping("/products")
    public ApiResult<PageResponse<ProductSummaryResponse>> browse(
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer cardSetId,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> allParameters) {

        return ApiResult.success(search.search(
                gameId, categoryId, cardSetId, productType, q, allParameters, sort, true, page, size));
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
