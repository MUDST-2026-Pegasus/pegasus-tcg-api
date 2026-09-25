package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Cart.CART;
import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Listing.LISTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.jooq.tables.records.CartItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.CartRecord;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code cart} and {@code cart_item}.
 */
@Repository
public class CartRepository {

    private final DSLContext dsl;
    private final Clock clock;

    @Autowired
    public CartRepository(DSLContext dsl, Clock clock) {
        this.dsl = dsl;
        this.clock = clock;
    }

    public CartRepository(DSLContext dsl) {
        this(dsl, Clock.systemUTC());
    }

    public Optional<Cart> findById(long id) {
        return dsl.selectFrom(CART)
                .where(CART.ID.eq(id))
                .fetchOptional()
                .map(CartRepository::toCart);
    }

    public Optional<Cart> findByUserId(long userId) {
        return dsl.selectFrom(CART)
                .where(CART.USER_ID.eq(userId))
                .fetchOptional()
                .map(CartRepository::toCart);
    }

    public Optional<Cart> findBySessionKey(String sessionKey) {
        return dsl.selectFrom(CART)
                .where(CART.SESSION_KEY.eq(sessionKey))
                .fetchOptional()
                .map(CartRepository::toCart);
    }

    public Cart getOrCreateForUser(long userId) {
        return findByUserId(userId).orElseGet(() -> {
            dsl.insertInto(CART)
                    .set(CART.USER_ID, userId)
                    .onConflict(CART.USER_ID)
                    .doNothing()
                    .execute();
            return findByUserId(userId).orElseThrow();
        });
    }

    public Cart createForSession(String sessionKey) {
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusDays(1);
        Optional<Cart> existing = findBySessionKey(sessionKey);
        if (existing.isPresent()) {
            touchGuestCartExpiry(existing.get().id(), expiresAt);
            return findById(existing.get().id()).orElse(existing.get());
        }
        dsl.insertInto(CART)
                .set(CART.SESSION_KEY, sessionKey)
                .set(CART.EXPIRES_AT, expiresAt)
                .onConflict(CART.SESSION_KEY)
                .doUpdate()
                .set(CART.EXPIRES_AT, expiresAt)
                .set(CART.UPDATED_AT, DSL.currentOffsetDateTime())
                .execute();
        return findBySessionKey(sessionKey).orElseThrow();
    }

    public void touchGuestCartExpiry(long cartId) {
        touchGuestCartExpiry(cartId, OffsetDateTime.now(clock).plusDays(1));
    }

    public void touchGuestCartExpiry(long cartId, OffsetDateTime expiresAt) {
        dsl.update(CART)
                .set(CART.EXPIRES_AT, DSL.when(CART.USER_ID.isNull(), expiresAt).otherwise(CART.EXPIRES_AT))
                .set(CART.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(CART.ID.eq(cartId))
                .execute();
    }

    public List<CartItem> findItemsByCartId(long cartId) {
        return dsl.selectFrom(CART_ITEM)
                .where(CART_ITEM.CART_ID.eq(cartId))
                .orderBy(CART_ITEM.ADDED_AT.asc(), CART_ITEM.ID.asc())
                .fetch(CartRepository::toCartItem);
    }

    public Optional<CartItem> findItemByIdAndCartId(long itemId, long cartId) {
        return dsl.selectFrom(CART_ITEM)
                .where(CART_ITEM.ID.eq(itemId))
                .and(CART_ITEM.CART_ID.eq(cartId))
                .fetchOptional()
                .map(CartRepository::toCartItem);
    }

    public Optional<CartItem> findItemByCartIdAndListingId(long cartId, long listingId) {
        return dsl.selectFrom(CART_ITEM)
                .where(CART_ITEM.CART_ID.eq(cartId))
                .and(CART_ITEM.LISTING_ID.eq(listingId))
                .fetchOptional()
                .map(CartRepository::toCartItem);
    }

    public CartItem upsertItem(long cartId, long listingId, int quantity, BigDecimal unitPriceAtAdd) {
        CartItemRecord record = dsl.insertInto(CART_ITEM)
                .set(CART_ITEM.CART_ID, cartId)
                .set(CART_ITEM.LISTING_ID, listingId)
                .set(CART_ITEM.QUANTITY, quantity)
                .set(CART_ITEM.UNIT_PRICE_AT_ADD, unitPriceAtAdd)
                .onConflict(CART_ITEM.CART_ID, CART_ITEM.LISTING_ID)
                .doUpdate()
                .set(CART_ITEM.QUANTITY, quantity)
                .set(CART_ITEM.UNIT_PRICE_AT_ADD, unitPriceAtAdd)
                .set(CART_ITEM.UPDATED_AT, DSL.currentOffsetDateTime())
                .returning()
                .fetchSingle();

        touchGuestCartExpiry(cartId);

        return toCartItem(record);
    }

    public CartItem updateItemQuantity(long itemId, long cartId, int quantity) {
        CartItemRecord record = dsl.update(CART_ITEM)
                .set(CART_ITEM.QUANTITY, quantity)
                .set(CART_ITEM.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(CART_ITEM.ID.eq(itemId))
                .and(CART_ITEM.CART_ID.eq(cartId))
                .returning()
                .fetchOne();

        if (record == null) {
            return null;
        }

        touchGuestCartExpiry(cartId);

        return toCartItem(record);
    }

    public boolean deleteItem(long itemId, long cartId) {
        boolean deleted = dsl.deleteFrom(CART_ITEM)
                .where(CART_ITEM.ID.eq(itemId))
                .and(CART_ITEM.CART_ID.eq(cartId))
                .execute() > 0;
        if (deleted) {
            touchGuestCartExpiry(cartId);
        }
        return deleted;
    }

    public void mergeGuestCartIntoUserCart(long guestCartId, long userCartId) {
        if (guestCartId == userCartId) {
            return;
        }
        List<CartItem> guestItems = findItemsByCartId(guestCartId);
        if (guestItems.isEmpty()) {
            dsl.deleteFrom(CART).where(CART.ID.eq(guestCartId)).execute();
            return;
        }

        List<CartItem> userItems = findItemsByCartId(userCartId);
        Map<Long, CartItem> userItemByListing = userItems.stream()
                .collect(Collectors.toMap(CartItem::listingId, item -> item, (a, b) -> a));

        List<Long> listingIds = guestItems.stream().map(CartItem::listingId).distinct().toList();
        Map<Long, Integer> stockMap = dsl.select(LISTING.ID, LISTING.QUANTITY_AVAILABLE)
                .from(LISTING)
                .where(LISTING.ID.in(listingIds))
                .fetchMap(LISTING.ID, r -> r.get(LISTING.QUANTITY_AVAILABLE));

        for (CartItem guestItem : guestItems) {
            long listingId = guestItem.listingId();
            Integer availableStock = stockMap.get(listingId);
            if (availableStock == null || availableStock <= 0) {
                continue;
            }

            CartItem existing = userItemByListing.get(listingId);
            if (existing != null) {
                int sum = existing.quantity() + guestItem.quantity();
                int finalQty = Math.min(sum, availableStock);
                if (finalQty > 0) {
                    dsl.update(CART_ITEM)
                            .set(CART_ITEM.QUANTITY, finalQty)
                            .set(CART_ITEM.UPDATED_AT, DSL.currentOffsetDateTime())
                            .where(CART_ITEM.ID.eq(existing.id()))
                            .execute();
                }
            } else {
                int finalQty = Math.min(guestItem.quantity(), availableStock);
                if (finalQty > 0) {
                    dsl.insertInto(CART_ITEM)
                            .set(CART_ITEM.CART_ID, userCartId)
                            .set(CART_ITEM.LISTING_ID, listingId)
                            .set(CART_ITEM.QUANTITY, finalQty)
                            .set(CART_ITEM.UNIT_PRICE_AT_ADD, guestItem.unitPriceAtAdd())
                            .execute();
                }
            }
        }
        dsl.deleteFrom(CART).where(CART.ID.eq(guestCartId)).execute();
    }

    /**
     * Re-pins an item to the price it sells for now, so the buyer can look at the
     * new figure and check out again [CR-3].
     */
    public CartItem updateItemPrice(long itemId, long cartId, BigDecimal unitPriceAtAdd) {
        CartItemRecord record = dsl.update(CART_ITEM)
                .set(CART_ITEM.UNIT_PRICE_AT_ADD, unitPriceAtAdd)
                .set(CART_ITEM.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(CART_ITEM.ID.eq(itemId))
                .and(CART_ITEM.CART_ID.eq(cartId))
                .returning()
                .fetchOne();
        return record == null ? null : toCartItem(record);
    }

    /**
     * Empties a basket without removing it. Checkout takes everything in the cart,
     * and the buyer keeps the same cart row for whatever they put in next.
     */
    public int deleteItemsByCartId(long cartId) {
        return dsl.deleteFrom(CART_ITEM)
                .where(CART_ITEM.CART_ID.eq(cartId))
                .execute();
    }

    public void deleteCart(long cartId) {
        dsl.deleteFrom(CART).where(CART.ID.eq(cartId)).execute();
    }

    /**
     * Housekeeping for signed-out baskets nobody came back for.
     *
     * <p>Anyone can create one of these without an account, so without a sweep the
     * table only ever grows. {@code cart_item} cascades from {@code cart}.
     */
    public int deleteExpiredGuestCarts(OffsetDateTime cutoff) {
        return dsl.deleteFrom(CART)
                .where(CART.USER_ID.isNull())
                .and(CART.EXPIRES_AT.isNotNull())
                .and(CART.EXPIRES_AT.lt(cutoff))
                .execute();
    }

    public record CartListingDetails(
            long listingId,
            String productName,
            String variantLabel,
            String sellerName,
            String imageKey) {
    }

    public Map<Long, CartListingDetails> findCartListingDetails(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(
                LISTING.ID,
                CATALOG_PRODUCT.NAME,
                CATALOG_VARIANT.LANGUAGE_CODE,
                CATALOG_VARIANT.FINISH,
                CATALOG_VARIANT.EDITION,
                CATALOG_VARIANT.PRINTING_NOTE,
                DSL.coalesce(USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.USERNAME),
                CATALOG_IMAGE.IMAGE_KEY)
                .from(LISTING)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(LISTING.CATALOG_VARIANT_ID))
                .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                .join(SELLER_PROFILE).on(SELLER_PROFILE.ID.eq(LISTING.SELLER_PROFILE_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(SELLER_PROFILE.USER_ID))
                .leftJoin(CATALOG_IMAGE).on(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(CATALOG_PRODUCT.ID)
                        .and(CATALOG_IMAGE.IS_PRIMARY.isTrue()))
                .where(LISTING.ID.in(listingIds))
                .fetchMap(LISTING.ID, r -> {
                    String languageCode = r.get(CATALOG_VARIANT.LANGUAGE_CODE);
                    String finish = r.get(CATALOG_VARIANT.FINISH);
                    String edition = r.get(CATALOG_VARIANT.EDITION);
                    String printingNote = r.get(CATALOG_VARIANT.PRINTING_NOTE);
                    StringBuilder label = new StringBuilder(languageCode != null ? languageCode : "EN");
                    if (finish != null && !"NOT_APPLICABLE".equals(finish)) {
                        label.append(" / ").append(finish);
                    }
                    if (edition != null && !"NOT_APPLICABLE".equals(edition)) {
                        label.append(" / ").append(edition);
                    }
                    if (printingNote != null && !printingNote.isBlank()) {
                        label.append(" / ").append(printingNote);
                    }
                    return new CartListingDetails(
                            r.get(LISTING.ID),
                            r.get(CATALOG_PRODUCT.NAME),
                            label.toString(),
                            r.value7(),
                            r.get(CATALOG_IMAGE.IMAGE_KEY));
                });
    }

    static Cart toCart(CartRecord r) {
        return new Cart(
                r.getId(),
                r.getUserId(),
                r.getSessionKey(),
                r.getCurrency(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getExpiresAt());
    }

    static CartItem toCartItem(CartItemRecord r) {
        return new CartItem(
                r.getId(),
                r.getCartId(),
                r.getListingId(),
                r.getQuantity(),
                r.getUnitPriceAtAdd(),
                r.getAddedAt(),
                r.getUpdatedAt());
    }
}
