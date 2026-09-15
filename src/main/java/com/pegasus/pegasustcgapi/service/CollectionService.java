package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.CollectionCard;
import com.pegasus.pegasustcgapi.dto.CollectionItemResponse;
import com.pegasus.pegasustcgapi.dto.CollectionSummaryResponse;
import com.pegasus.pegasustcgapi.dto.PublicCollectionItemResponse;
import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import com.pegasus.pegasustcgapi.model.CollectionItem;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogProductRepository;
import com.pegasus.pegasustcgapi.repository.CatalogVariantRepository;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository.CollectionItemFields;
import com.pegasus.pegasustcgapi.repository.CollectionItemRepository.CollectionQuery;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cards a person keeps [RQ-3, RQ-9].
 *
 * <p>Only hand-added cards for now. Cards that arrive through an order wait for
 * the order module, which is what knows when a sale can no longer be returned.
 *
 * <p>Every card must already be in the shared catalogue — there is no free-text
 * name — so everyone holding the same printing points at the same variant.
 */
@Service
public class CollectionService {

    private final CollectionItemRepository items;
    private final CatalogVariantRepository variants;
    private final CatalogProductRepository products;
    private final CatalogImageRepository catalogImages;
    private final UserRepository users;
    private final StorageService storage;
    private final Clock clock;

    public CollectionService(
            CollectionItemRepository items,
            CatalogVariantRepository variants,
            CatalogProductRepository products,
            CatalogImageRepository catalogImages,
            UserRepository users,
            StorageService storage,
            Clock clock) {

        this.items = items;
        this.variants = variants;
        this.products = products;
        this.catalogImages = catalogImages;
        this.users = users;
        this.storage = storage;
        this.clock = clock;
    }

    // ---------- the owner's own view ----------

    /** @param publicItem null for every card, or filter to the public or the private ones */
    public PageResponse<CollectionItemResponse> mine(
            long userId, Short gameId, Long variantId, Boolean publicItem, int page, int size) {

        Paging paging = Paging.of(page, size);
        CollectionQuery query = new CollectionQuery(
                userId, gameId, variantId, publicItem, paging.size(), paging.offset());

        List<CollectionItemResponse> rows = render(items.findPage(query),
                (item, card) -> CollectionItemResponse.of(item, card, signed(item.imageKey())));

        return PageResponse.of(rows, paging.page(), paging.size(), items.count(query));
    }

    public CollectionItemResponse get(long userId, long itemId) {
        CollectionItem item = require(userId, itemId);
        return render(List.of(item),
                (row, card) -> CollectionItemResponse.of(row, card, signed(row.imageKey()))).getFirst();
    }

    public CollectionSummaryResponse summary(long userId) {
        return CollectionSummaryResponse.from(items.summarise(userId));
    }

    /**
     * Adds a card by hand. Adding the same printing twice makes a second row, on
     * purpose: each time may carry its own price, date and note, and merging would
     * silently keep only one of them.
     */
    @Transactional
    public CollectionItemResponse add(long userId, CollectionItemFields fields) {
        requireActiveVariant(fields.catalogVariantId());
        requireGradingConsistent(fields);
        requirePhotoUsable(userId, fields.imageKey());

        long id = items.insertManual(userId, fields);
        return get(userId, id);
    }

    /**
     * Edits a card. A printing that has since been retired stays editable — only
     * moving a card <em>onto</em> a retired printing is refused.
     */
    @Transactional
    public CollectionItemResponse update(long userId, long itemId, CollectionItemFields fields) {
        CollectionItem existing = require(userId, itemId);

        boolean variantChanged = existing.catalogVariantId() != fields.catalogVariantId();
        if (existing.fromPurchase() && (variantChanged || existing.quantity() != fields.quantity())) {
            throw new ConflictException(ErrorCode.COLLECTION_ITEM_FROM_PURCHASE);
        }

        // An unchanged printing needs no lookup: the foreign key is ON DELETE RESTRICT,
        // so it still exists, and a retired one is allowed to stay.
        if (variantChanged) {
            requireActiveVariant(fields.catalogVariantId());
        }
        requireGradingConsistent(fields);
        if (!Objects.equals(existing.imageKey(), fields.imageKey())) {
            requirePhotoUsable(userId, fields.imageKey());
        }

        items.update(itemId, userId, fields);
        return get(userId, itemId);
    }

    /**
     * Soft delete. The row stays so that, once purchases add cards, a retried
     * grant collides with it and skips, instead of bringing back a card the owner
     * already threw away. The photo stays in storage for the same reason.
     */
    @Transactional
    public void remove(long userId, long itemId) {
        if (!items.softDelete(itemId, userId, OffsetDateTime.now(clock))) {
            throw new NotFoundException(ErrorCode.COLLECTION_ITEM_NOT_FOUND);
        }
    }

    // ---------- what a visitor sees ----------

    /**
     * The cards someone chose to show. A missing, deleted or suspended account is
     * simply not found, so the endpoint cannot be used to tell those apart.
     */
    public PageResponse<PublicCollectionItemResponse> publicCollection(
            String username, Short gameId, int page, int size) {

        AuthUser owner = users.findByUsername(username.trim().toLowerCase(Locale.ROOT))
                .filter(user -> user.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        Paging paging = Paging.of(page, size);
        CollectionQuery query = new CollectionQuery(
                owner.id(), gameId, null, Boolean.TRUE, paging.size(), paging.offset());

        List<PublicCollectionItemResponse> rows = render(items.findPage(query),
                (item, card) -> PublicCollectionItemResponse.of(item, card, signed(item.imageKey())));

        return PageResponse.of(rows, paging.page(), paging.size(), items.count(query));
    }

    // ---------- rules ----------

    private CollectionItem require(long userId, long itemId) {
        return items.findOfUser(itemId, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.COLLECTION_ITEM_NOT_FOUND));
    }

    /**
     * A card being added, or moved to another printing, has to name one that is in
     * the catalogue and still on offer. A card someone already keeps is not held
     * to that: it does not vanish because an admin later retired its printing.
     */
    private void requireActiveVariant(long variantId) {
        CatalogVariant variant = variants.findById(variantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VARIANT_NOT_FOUND));

        if (!variant.active()) {
            throw new ConflictException(ErrorCode.VARIANT_INACTIVE);
        }
    }

    /**
     * Two rules the collection table does not state itself. A grade means nothing
     * without the company that gave it. And a certificate number belongs to one
     * slab, so a row carrying one is exactly one card.
     */
    private static void requireGradingConsistent(CollectionItemFields fields) {
        if (fields.gradeValue() != null && fields.gradingCompany() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "gradeValue needs the gradingCompany that gave it");
        }
        if (fields.certNumber() != null && fields.quantity() != 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A certified slab is one card: a row with a certNumber must have quantity 1");
        }
    }

    /**
     * A photo has to be a finished collection upload of a size and type that
     * purpose allows, and not already belong to someone else's card. Both matter
     * because the photo can end up on a public page, and that page hands out a
     * signed URL with the key readable inside it.
     */
    private void requirePhotoUsable(long userId, String imageKey) {
        if (imageKey == null) {
            return;
        }
        storage.requireUploadedFor(UploadPurpose.COLLECTION_IMAGE, imageKey);
        if (items.imageKeyUsedByAnother(imageKey, userId)) {
            throw new ConflictException(ErrorCode.IMAGE_KEY_IN_USE);
        }
    }

    // ---------- rendering ----------

    /**
     * Attaches the card to each row with one query for the variants, one for the
     * products and one for the art, however many rows the page has.
     */
    private <T> List<T> render(List<CollectionItem> rows, BiFunction<CollectionItem, CollectionCard, T> shape) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, CatalogVariant> variantsById = variants.findByIds(
                rows.stream().map(CollectionItem::catalogVariantId).distinct().toList());

        List<Long> productIds = variantsById.values().stream()
                .map(CatalogVariant::catalogProductId).distinct().toList();
        Map<Long, CatalogProduct> productsById = products.findByIds(productIds);
        Map<Long, String> artByProduct = catalogImages.primaryKeysOf(productIds);

        return rows.stream().map(item -> {
            CatalogVariant variant = variantsById.get(item.catalogVariantId());
            CatalogProduct product = productsById.get(variant.catalogProductId());
            CollectionCard card = CollectionCard.of(variant, product, signed(artByProduct.get(product.id())));
            return shape.apply(item, card);
        }).toList();
    }

    private String signed(String imageKey) {
        return imageKey == null ? null : storage.presignDownload(imageKey);
    }
}
