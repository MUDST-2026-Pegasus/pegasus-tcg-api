package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.Slugs;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository.ProductFields;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The card as a concept, owned by the platform [RQ-3].
 *
 * <p>Everything here is about keeping one truth per card: one slug, one set of
 * attribute values that match the game's registry, and a category and set that
 * actually belong to that game. Sellers never write here — they point listings at
 * a variant of what is already catalogued, which is what makes two sellers'
 * copies of one card comparable at all.
 */
@Service
public class CatalogProductService {

    private static final Logger log = LoggerFactory.getLogger(CatalogProductService.class);

    private final CatalogProductRepository products;
    private final GameService games;
    private final CatalogTaxonomyService taxonomy;
    private final ProductAttributeValidator attributeValidator;

    public CatalogProductService(
            CatalogProductRepository products,
            GameService games,
            CatalogTaxonomyService taxonomy,
            ProductAttributeValidator attributeValidator) {

        this.products = products;
        this.games = games;
        this.taxonomy = taxonomy;
        this.attributeValidator = attributeValidator;
    }

    public CatalogProduct require(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    public CatalogProduct requireBySlug(String slug) {
        return products.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * Accepts either form of identifier, because a URL carries the slug and an
     * admin screen carries the id.
     */
    public CatalogProduct requireByIdOrSlug(String idOrSlug) {
        try {
            return require(Long.parseLong(idOrSlug));
        } catch (NumberFormatException notAnId) {
            return requireBySlug(idOrSlug);
        }
    }

    @Transactional
    public CatalogProduct create(ProductFields requested, Long createdBy) {
        games.require(requested.gameId());
        requireTaxonomyOfGame(requested.gameId(), requested.categoryId(), requested.cardSetId());

        Map<String, Object> attributes = attributeValidator.validate(
                games.attributesOf(requested.gameId()), requested.attributes());

        String slug = Slugs.unique(requested.slug(), products::slugTaken,
                requested.name(), requested.cardNumber() == null ? "" : requested.cardNumber());

        long id = products.insert(withDerived(requested, slug, attributes), createdBy);
        log.info("Catalogued product {} as id {} ({})", requested.name(), id, slug);
        return require(id);
    }

    /**
     * The game and the slug are fixed once the product exists: the first because
     * the stored attributes were checked against that game's registry, the second
     * because links point at it.
     */
    @Transactional
    public CatalogProduct update(long productId, ProductFields requested) {
        CatalogProduct existing = require(productId);
        requireTaxonomyOfGame(existing.gameId(), requested.categoryId(), requested.cardSetId());

        Map<String, Object> attributes = attributeValidator.validate(
                games.attributesOf(existing.gameId()), requested.attributes());

        products.update(productId, withDerived(requested, existing.slug(), attributes));
        return require(productId);
    }

    /**
     * A category may be this game's or a cross-game one; a set must be this
     * game's. Both are FK-checked by the database, but only for existence — that
     * the set belongs to the right game is a rule only this layer can state.
     */
    private void requireTaxonomyOfGame(short gameId, int categoryId, Integer cardSetId) {
        CatalogCategory category = taxonomy.requireCategory(categoryId);
        if (!category.isCrossGame() && category.gameId() != gameId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Category " + category.code() + " belongs to another game");
        }

        if (cardSetId == null) {
            return;
        }
        CardSet cardSet = taxonomy.requireCardSet(cardSetId);
        if (cardSet.gameId() != gameId) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Card set " + cardSet.code() + " belongs to another game");
        }
    }

    private static ProductFields withDerived(
            ProductFields requested, String slug, Map<String, Object> attributes) {

        return new ProductFields(
                requested.gameId(),
                requested.categoryId(),
                requested.cardSetId(),
                requested.productType(),
                requested.name().trim(),
                blankToNull(requested.nameLocal()),
                slug,
                blankToNull(requested.cardNumber()),
                blankToNull(requested.rarityCode()),
                blankToNull(requested.description()),
                attributes,
                requested.active());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
