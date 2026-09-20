package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.Cart.CART;
import static com.pegasus.pegasustcgapi.jooq.tables.CartItem.CART_ITEM;

import com.pegasus.pegasustcgapi.jooq.tables.records.CartItemRecord;
import com.pegasus.pegasustcgapi.jooq.tables.records.CartRecord;
import com.pegasus.pegasustcgapi.model.Cart;
import com.pegasus.pegasustcgapi.model.CartItem;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
        for (CartItem guestItem : guestItems) {
            dsl.insertInto(CART_ITEM)
                    .set(CART_ITEM.CART_ID, userCartId)
                    .set(CART_ITEM.LISTING_ID, guestItem.listingId())
                    .set(CART_ITEM.QUANTITY, guestItem.quantity())
                    .set(CART_ITEM.UNIT_PRICE_AT_ADD, guestItem.unitPriceAtAdd())
                    .onConflict(CART_ITEM.CART_ID, CART_ITEM.LISTING_ID)
                    .doUpdate()
                    .set(CART_ITEM.QUANTITY, CART_ITEM.QUANTITY.plus(guestItem.quantity()))
                    .set(CART_ITEM.UPDATED_AT, DSL.currentOffsetDateTime())
                    .execute();
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
