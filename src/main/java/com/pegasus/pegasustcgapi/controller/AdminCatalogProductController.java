package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.CatalogImageRequest;
import com.pegasus.pegasustcgapi.dto.CatalogImageResponse;
import com.pegasus.pegasustcgapi.dto.ProductDetailResponse;
import com.pegasus.pegasustcgapi.dto.ProductRequest;
import com.pegasus.pegasustcgapi.dto.ProductSummaryResponse;
import com.pegasus.pegasustcgapi.dto.VariantRequest;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.ProductType;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.CatalogImageService;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.service.CatalogSearchService;
import com.pegasus.pegasustcgapi.service.CatalogVariantService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
 * <p>Nothing deletes a product or a variant — listings, orders and collections
 * point at them — so what is no longer offered is deactivated. Images are the
 * exception: an image is a file, and removing it removes the file too.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/catalog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogProductController {

    private final CatalogProductService products;
    private final CatalogVariantService variants;
    private final CatalogImageService images;
    private final CatalogSearchService search;

    public AdminCatalogProductController(CatalogProductService products,
            CatalogVariantService variants, CatalogImageService images,
            CatalogSearchService search) {

        this.products = products;
        this.variants = variants;
        this.images = images;
        this.search = search;
    }

    // ---------- products ----------

    /**
     * The same browse as the public one, but including what has been
     * deactivated: a product retired by mistake is otherwise reachable only by
     * someone who already knows its id.
     */
    @GetMapping("/products")
    public ApiResult<PageResponse<ProductSummaryResponse>> browse(
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer cardSetId,
            @RequestParam(required = false) ProductType productType,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> allParameters) {

        return ApiResult.success(search.search(gameId, categoryId, cardSetId, productType, q,
                allParameters, sort, activeOnly, page, size));
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
            @PathVariable long productId, @Valid @RequestBody ProductRequest request) {

        return ApiResult.success("Product updated", products.update(productId, request.toFields()));
    }

    // ---------- variants ----------

    /** Unlike the public list, this one shows retired printings too. */
    @GetMapping("/products/{productId}/variants")
    public ApiResult<List<CatalogVariant>> variants(@PathVariable long productId) {
        return ApiResult.success(variants.listOfProduct(productId, true));
    }

    @PostMapping("/products/{productId}/variants")
    public ResponseEntity<ApiResult<CatalogVariant>> createVariant(
            @PathVariable long productId, @Valid @RequestBody VariantRequest request) {

        CatalogVariant created = variants.create(productId, request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Variant added", created));
    }

    @PutMapping("/products/{productId}/variants/{variantId}")
    public ApiResult<CatalogVariant> updateVariant(
            @PathVariable long productId,
            @PathVariable long variantId,
            @Valid @RequestBody VariantRequest request) {

        return ApiResult.success("Variant updated",
                variants.update(productId, variantId, request.toFields()));
    }

    // ---------- images ----------

    /**
     * Attaches a file that was already uploaded with a presigned URL. The upload
     * is confirmed against storage before the key is stored, so a key for a file
     * that never arrived is refused rather than saved as a broken image.
     */
    @PostMapping("/products/{productId}/images")
    public ResponseEntity<ApiResult<CatalogImageResponse>> addImage(
            @PathVariable long productId, @Valid @RequestBody CatalogImageRequest request) {

        CatalogImageResponse created = images.add(productId, request.toFields());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success("Image added", created));
    }

    @PutMapping("/products/{productId}/images/{imageId}/primary")
    public ApiResult<CatalogImageResponse> makePrimary(
            @PathVariable long productId, @PathVariable long imageId) {

        return ApiResult.success("Primary image set", images.makePrimary(productId, imageId));
    }

    /** Removes the row and the object behind it; the next image takes over as primary. */
    @DeleteMapping("/products/{productId}/images/{imageId}")
    public ApiResult<Void> deleteImage(@PathVariable long productId, @PathVariable long imageId) {
        images.delete(productId, imageId);
        return ApiResult.success("Image deleted", null);
    }
}
