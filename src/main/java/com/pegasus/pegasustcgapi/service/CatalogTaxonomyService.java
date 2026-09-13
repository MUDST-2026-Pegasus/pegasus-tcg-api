package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.Slugs;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.repository.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.CardSetRepository.CardSetFields;
import com.pegasus.pegasustcgapi.repository.CatalogCategoryRepository;
import com.pegasus.pegasustcgapi.repository.CatalogCategoryRepository.CategoryFields;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two shelves a catalogue entry sits on: which category it belongs to, and
 * which release it came from.
 *
 * <p>Neither is ever deleted. {@code catalog_product} points at both with
 * ON DELETE RESTRICT, so a category that is no longer offered is deactivated and
 * the products filed under it keep resolving.
 */
@Service
public class CatalogTaxonomyService {

    /** {@code catalog_category.slug} is varchar(120). */
    private static final int SLUG_WIDTH = 120;

    private final CatalogCategoryRepository categories;
    private final CardSetRepository cardSets;
    private final GameService games;

    public CatalogTaxonomyService(
            CatalogCategoryRepository categories, CardSetRepository cardSets, GameService games) {

        this.categories = categories;
        this.cardSets = cardSets;
        this.games = games;
    }

    // ---------- categories ----------

    /** @param gameId null lists every category; a game also gets the cross-game ones. */
    public List<CatalogCategory> categories(Short gameId, boolean includeInactive) {
        if (gameId != null) {
            games.require(gameId);
        }
        return categories.find(gameId, includeInactive);
    }

    public CatalogCategory requireCategory(int categoryId) {
        return categories.findById(categoryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    @Transactional
    public CatalogCategory createCategory(CategoryFields requested) {
        if (requested.gameId() != null) {
            games.require(requested.gameId());
        }
        requireParentUsable(requested.parentId(), null);

        String code = normaliseCode(requested.code());
        if (categories.codeTaken(requested.gameId(), code, null)) {
            throw new ConflictException(ErrorCode.CATEGORY_CODE_ALREADY_USED);
        }

        int id = categories.insert(fields(requested, code, null));
        return requireCategory(id);
    }

    @Transactional
    public CatalogCategory updateCategory(int categoryId, CategoryFields requested) {
        CatalogCategory existing = requireCategory(categoryId);
        requireParentUsable(requested.parentId(), categoryId);

        String code = normaliseCode(requested.code());
        if (categories.codeTaken(existing.gameId(), code, categoryId)) {
            throw new ConflictException(ErrorCode.CATEGORY_CODE_ALREADY_USED);
        }

        categories.update(categoryId, fields(requested, code, existing.slug()));
        return requireCategory(categoryId);
    }

    // ---------- card sets ----------

    public List<CardSet> cardSets(short gameId) {
        games.require(gameId);
        return cardSets.findByGameId(gameId);
    }

    public CardSet requireCardSet(int cardSetId) {
        return cardSets.findById(cardSetId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.CARD_SET_NOT_FOUND));
    }

    @Transactional
    public CardSet createCardSet(short gameId, CardSetFields requested) {
        games.require(gameId);

        String code = normaliseCode(requested.code());
        if (cardSets.codeTaken(gameId, code, null)) {
            throw new ConflictException(ErrorCode.CARD_SET_CODE_ALREADY_USED);
        }

        int id = cardSets.insert(gameId, withCode(requested, code));
        return requireCardSet(id);
    }

    @Transactional
    public CardSet updateCardSet(int cardSetId, CardSetFields requested) {
        CardSet existing = requireCardSet(cardSetId);

        String code = normaliseCode(requested.code());
        if (cardSets.codeTaken(existing.gameId(), code, cardSetId)) {
            throw new ConflictException(ErrorCode.CARD_SET_CODE_ALREADY_USED);
        }

        cardSets.update(cardSetId, withCode(requested, code));
        return requireCardSet(cardSetId);
    }

    /**
     * A parent has to exist, and cannot be the category itself.
     *
     * <p>Only one level is checked, so a deeper loop is still possible in theory.
     * Categories are a short, admin-curated list, and the check that would rule it
     * out reads the whole chain on every save.
     */
    private void requireParentUsable(Integer parentId, Integer categoryId) {
        if (parentId == null) {
            return;
        }
        if (categoryId != null && parentId.equals(categoryId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A category cannot be its own parent");
        }
        requireCategory(parentId);
    }

    /** Keeps the existing slug on update: it is in URLs, so it is set once. */
    private static CategoryFields fields(CategoryFields requested, String code, String existingSlug) {
        String slug = existingSlug != null
                ? existingSlug
                : Slugs.unique(requested.slug(), SLUG_WIDTH, unused -> false, requested.name());

        return new CategoryFields(requested.gameId(), requested.parentId(), code,
                requested.name().trim(), slug, requested.displayOrder(), requested.active());
    }

    private static CardSetFields withCode(CardSetFields requested, String code) {
        return new CardSetFields(code, requested.name().trim(), blankToNull(requested.nameLocal()),
                requested.releaseDate(), requested.totalCards(), blankToNull(requested.logoUrl()));
    }

    private static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
