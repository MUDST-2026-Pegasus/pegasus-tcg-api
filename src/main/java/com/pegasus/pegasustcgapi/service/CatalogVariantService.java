package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.Slugs;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository.VariantFields;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rows that are actually traded [RQ-5].
 *
 * <p>Two things are guarded here. A variant's identity — product, language,
 * finish, edition, printing note — may exist only once, or the same print ends up
 * with two market prices and neither is right. And its SKU is unique across the
 * platform, because that is the code people quote to each other when an id would
 * mean nothing.
 */
@Service
public class CatalogVariantService {

    private static final Logger log = LoggerFactory.getLogger(CatalogVariantService.class);

    private final CatalogVariantRepository variants;
    private final CatalogProductService products;
    private final GameService games;
    private final CatalogTaxonomyService taxonomy;

    public CatalogVariantService(
            CatalogVariantRepository variants,
            CatalogProductService products,
            GameService games,
            CatalogTaxonomyService taxonomy) {

        this.variants = variants;
        this.products = products;
        this.games = games;
        this.taxonomy = taxonomy;
    }

    public List<CatalogVariant> listOfProduct(long productId, boolean includeInactive) {
        products.require(productId);
        return variants.findByProductId(productId, includeInactive);
    }

    public CatalogVariant require(long variantId) {
        return variants.findById(variantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VARIANT_NOT_FOUND));
    }

    /** Reads the variant through its product, so an id from another card is simply not found. */
    public CatalogVariant requireOfProduct(long productId, long variantId) {
        return variants.findById(variantId)
                .filter(variant -> variant.catalogProductId() == productId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VARIANT_NOT_FOUND));
    }

    @Transactional
    public CatalogVariant create(long productId, VariantFields requested) {
        CatalogProduct product = products.require(productId);
        VariantFields fields = normalise(requested);

        requireIdentityFree(productId, fields, null);

        String sku = fields.sku() == null || fields.sku().isBlank()
                ? generateSku(product, fields)
                : requireSkuFree(fields.sku(), null);

        long id = variants.insert(productId, withSku(fields, sku));
        log.info("Added variant {} to product {}", sku, productId);
        return require(id);
    }

    @Transactional
    public CatalogVariant update(long productId, long variantId, VariantFields requested) {
        CatalogVariant existing = requireOfProduct(productId, variantId);
        VariantFields fields = normalise(requested);

        requireIdentityFree(productId, fields, variantId);

        String sku = fields.sku() == null || fields.sku().isBlank()
                ? existing.sku()
                : requireSkuFree(fields.sku(), variantId);

        variants.update(variantId, withSku(fields, sku));
        return require(variantId);
    }

    private void requireIdentityFree(long productId, VariantFields fields, Long exceptId) {
        boolean taken = variants.identityTaken(productId, fields.languageCode(), fields.finish(),
                fields.edition(), fields.printingNote(), exceptId);

        if (taken) {
            throw new ConflictException(ErrorCode.VARIANT_ALREADY_EXISTS,
                    "This product already has a " + fields.languageCode() + " / " + fields.finish()
                            + " / " + fields.edition() + " variant");
        }
    }

    private String requireSkuFree(String sku, Long exceptId) {
        String normalised = sku.trim().toUpperCase(Locale.ROOT);
        if (variants.skuTaken(normalised, exceptId)) {
            throw new ConflictException(ErrorCode.SKU_ALREADY_USED);
        }
        return normalised;
    }

    /**
     * Builds the code an admin would have typed — game, set, card number,
     * language, finish — so SKUs read the same whoever entered the card. A clash
     * picks up a numeric suffix rather than failing the request.
     */
    private String generateSku(CatalogProduct product, VariantFields fields) {
        String setCode = product.cardSetId() == null
                ? null
                : taxonomy.requireCardSet(product.cardSetId()).code();

        String[] parts = Stream.of(
                        games.require(product.gameId()).code(),
                        setCode,
                        product.cardNumber(),
                        fields.languageCode(),
                        fields.finish().name(),
                        fields.edition() == CardEdition.NOT_APPLICABLE ? null : fields.edition().name(),
                        fields.printingNote())
                .filter(part -> part != null && !part.isBlank())
                .toArray(String[]::new);

        String generated = Slugs.unique(null, candidate -> variants.skuTaken(candidate, null), parts);
        return generated.toUpperCase(Locale.ROOT);
    }

    private static VariantFields normalise(VariantFields requested) {
        return new VariantFields(
                requested.sku(),
                requested.languageCode().trim().toUpperCase(Locale.ROOT),
                requested.finish(),
                requested.edition(),
                blankToNull(requested.printingNote()),
                blankToNull(requested.barcode()),
                blankToNull(requested.imageUrl()),
                requested.active());
    }

    private static VariantFields withSku(VariantFields fields, String sku) {
        return new VariantFields(sku, fields.languageCode(), fields.finish(), fields.edition(),
                fields.printingNote(), fields.barcode(), fields.imageUrl(), fields.active());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
