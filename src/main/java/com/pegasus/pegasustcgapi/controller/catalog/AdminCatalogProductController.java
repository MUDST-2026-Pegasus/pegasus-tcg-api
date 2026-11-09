package com.pegasus.pegasustcgapi.controller.catalog;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.CatalogCardEffectRequest;
import com.pegasus.pegasustcgapi.dto.catalog.CatalogCardEffectResponse;
import com.pegasus.pegasustcgapi.dto.catalog.CatalogCardEffectsRequest;
import com.pegasus.pegasustcgapi.dto.catalog.CatalogImageRequest;
import com.pegasus.pegasustcgapi.dto.catalog.CatalogImageResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductDetailResponse;
import com.pegasus.pegasustcgapi.dto.catalog.ProductRequest;
import com.pegasus.pegasustcgapi.dto.catalog.AdminProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.catalog.VariantRequest;
import com.pegasus.pegasustcgapi.model.catalog.CatalogProduct;
import com.pegasus.pegasustcgapi.model.catalog.CatalogVariant;
import com.pegasus.pegasustcgapi.model.catalog.AdminSaleStatus;
import com.pegasus.pegasustcgapi.model.catalog.ProductType;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.catalog.CatalogCardEffectService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogImageService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogProductService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.catalog.ProductBrowse;
import com.pegasus.pegasustcgapi.service.listing.AdminCardListingService;
import com.pegasus.pegasustcgapi.service.catalog.CatalogVariantService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Filling the catalogue [RQ-3]. Sellers never write here: they list against what
 * is already catalogued, which is the whole reason two sellers' copies of one
 * card can be compared at all.
 *
 * <p>A product can be deleted only while nothing uses it; once listings, orders
 * or collections point at its variants, what is no longer offered is
 * deactivated instead. Variants are never deleted on their own. Images are
 * always deletable: an image is a file, and removing it removes the file too.
 */
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/catalog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogProductController {

    private final CatalogProductService products;
    private final CatalogVariantService variants;
    private final CatalogImageService images;
    private final CatalogSearchService search;
    private final CatalogCardEffectService effects;
    private final AdminCardListingService listings;

    public AdminCatalogProductController(CatalogProductService products,
            CatalogVariantService variants, CatalogImageService images,
            CatalogSearchService search, CatalogCardEffectService effects,
            AdminCardListingService listings) {

        this.products = products;
        this.variants = variants;
        this.images = images;
        this.search = search;
        this.effects = effects;
        this.listings = listings;
    }

    // ---------- products ----------

    /**
     * The same browse as the public one, but including what has been
     * deactivated: a product retired by mistake is otherwise reachable only by
     * someone who already knows its id.
     */
    @GetMapping("/products")
    public ApiResult<PageResponse<AdminProductSummaryResponse>> browse(
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer cardSetId,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) AdminSaleStatus saleStatus,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> allParameters) {

        return ApiResult.success(listings.withStock(search.search(new ProductBrowse(
                gameId == null ? null : Set.of(gameId), categoryId, cardSetId, productType, q,
                allParameters, sort, activeOnly, false, null, null, null, page, size, saleStatus))));
    }

    /**
     * The card page as an admin needs it: found even when retired, with retired
     * printings included. An update replaces the whole row, so an edit form has
     * to load everything first, and the public page no longer finds a retired one.
     */
    @GetMapping("/products/{idOrSlug}")
    public ApiResult<ProductDetailResponse> product(@PathVariable String idOrSlug) {
        CatalogProduct product = products.requireByIdOrSlug(idOrSlug);

        return ApiResult.success(new ProductDetailResponse(
                product,
                variants.listOfProduct(product.id(), true),
                images.listOfProduct(product.id(), true)));
    }

    @PostMapping("/products")
    public ResponseEntity<ApiResult<CatalogProduct>> createProduct(
            @Valid @RequestBody ProductRequest request, AuthPrincipal principal) {

        CatalogProduct created = products.create(request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Product catalogued", created));
    }

    /** The game and the slug are fixed at creation; everything else is editable. */
    @PutMapping("/products/{productId}")
    public ApiResult<CatalogProduct> updateProduct(
            @PathVariable long productId, @Valid @RequestBody ProductRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Product updated",
                products.update(productId, request.toFields(), principal.userId()));
    }

    /**
     * For a product catalogued twice or by mistake. Refused with PRODUCT_IN_USE
     * once a listing, an order or a collection points at it; retire it then.
     */
    @DeleteMapping("/products/{productId}")
    public ApiResult<Void> deleteProduct(@PathVariable long productId, AuthPrincipal principal) {
        products.delete(productId, principal.userId());
        return ApiResult.success("Product deleted", null);
    }

    // ---------- variants ----------

    /** Unlike the public list, this one shows retired printings too. */
    @GetMapping("/products/{productId}/variants")
    public ApiResult<List<CatalogVariant>> variants(@PathVariable long productId) {
        return ApiResult.success(variants.listOfProduct(productId, true));
    }

    @PostMapping("/products/{productId}/variants")
    public ResponseEntity<ApiResult<CatalogVariant>> createVariant(
            @PathVariable long productId, @Valid @RequestBody VariantRequest request,
            AuthPrincipal principal) {

        CatalogVariant created = variants.create(productId, request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Variant added", created));
    }

    @PutMapping("/products/{productId}/variants/{variantId}")
    public ApiResult<CatalogVariant> updateVariant(
            @PathVariable long productId,
            @PathVariable long variantId,
            @Valid @RequestBody VariantRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Variant updated",
                variants.update(productId, variantId, request.toFields(), principal.userId()));
    }

    // ---------- images ----------

    /**
     * Attaches a file that was already uploaded with a presigned URL. The upload
     * is confirmed against storage before the key is stored, so a key for a file
     * that never arrived is refused rather than saved as a broken image.
     */
    @PostMapping("/products/{productId}/images")
    public ResponseEntity<ApiResult<CatalogImageResponse>> addImage(
            @PathVariable long productId, @Valid @RequestBody CatalogImageRequest request,
            AuthPrincipal principal) {

        CatalogImageResponse created = images.add(productId, request.toFields(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Image added", created));
    }

    @PutMapping("/products/{productId}/images/{imageId}/primary")
    public ApiResult<CatalogImageResponse> makePrimary(
            @PathVariable long productId, @PathVariable long imageId, AuthPrincipal principal) {

        return ApiResult.success("Primary image set",
                images.makePrimary(productId, imageId, principal.userId()));
    }

    /** Removes the row and the object behind it; the next image takes over as primary. */
    @DeleteMapping("/products/{productId}/images/{imageId}")
    public ApiResult<Void> deleteImage(@PathVariable long productId, @PathVariable long imageId,
            AuthPrincipal principal) {
        images.delete(productId, imageId, principal.userId());
        return ApiResult.success("Image deleted", null);
    }

    // ---------- abilities & attacks ----------

    /** Unlike the public list, this one is found for a retired product too. */
    @GetMapping("/products/{productId}/effects")
    public ApiResult<List<CatalogCardEffectResponse>> effects(@PathVariable long productId) {
        return ApiResult.success(effects.listOfProduct(productId));
    }

    /** Replaces the whole list; each effect's place in it is its display order. */
    @PutMapping("/products/{productId}/effects")
    public ApiResult<List<CatalogCardEffectResponse>> replaceEffects(
            @PathVariable long productId, @Valid @RequestBody CatalogCardEffectsRequest request,
            AuthPrincipal principal) {

        return ApiResult.success("Abilities and attacks saved", effects.replace(productId,
                request.effects().stream().map(CatalogCardEffectRequest::toFields).toList(),
                principal.userId()));
    }
}
