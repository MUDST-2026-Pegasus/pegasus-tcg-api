package com.pegasus.pegasustcgapi.support;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static com.pegasus.pegasustcgapi.jooq.tables.PlatformSetting.PLATFORM_SETTING;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.dto.ListingUnitResponse;
import com.pegasus.pegasustcgapi.dto.SellerListingResponse;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.ListingStatus;
import com.pegasus.pegasustcgapi.model.MarketKey;
import com.pegasus.pegasustcgapi.model.PricingMode;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Details;
import com.pegasus.pegasustcgapi.repository.ListingRepository.Pricing;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.AddressService;
import com.pegasus.pegasustcgapi.service.JwtService;
import com.pegasus.pegasustcgapi.service.ListingService;
import com.pegasus.pegasustcgapi.service.ListingUnitService;
import com.pegasus.pegasustcgapi.service.ListingUnitService.StockIn;
import com.pegasus.pegasustcgapi.service.PlatformSettingService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * Seeds the rows integration tests stand on — people, the catalogue, stock on sale —
 * through the same services production uses where there is one, and straight into
 * the table where there is not (accounts, games, products).
 *
 * <p>Every name is suffixed with a tag unique to this JVM, so tests sharing the one
 * database never collide on a unique key or see each other's rows.
 */
public class TestData {

    /** The seeded values of the settings tests are allowed to change. */
    private static final Map<String, String> DEFAULT_SETTINGS = Map.of(
            PlatformSettingService.ORDER_PAYMENT_TIMEOUT_MINUTES, "60",
            PlatformSettingService.COMMISSION_DEFAULT_RATE, "5.0");

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime());

    private final DSLContext dsl;
    private final ListingService listings;
    private final ListingUnitService units;
    private final AddressService addresses;
    private final JwtService jwt;

    public TestData(DSLContext dsl, ListingService listings, ListingUnitService units,
            AddressService addresses, JwtService jwt) {
        this.dsl = dsl;
        this.listings = listings;
        this.units = units;
        this.addresses = addresses;
        this.jwt = jwt;
    }

    /** Short, lower-case and unique for the JVM: fits every code and slug column. */
    public String tag() {
        return Long.toString(SEQUENCE.incrementAndGet(), 36);
    }

    // ---------- people ----------

    public record Seller(long userId, long profileId, AuthPrincipal principal) {
    }

    public AuthPrincipal buyer() {
        return principal(user("buyer"), Set.of(RoleCode.BUYER));
    }

    /** A verified seller, open for business, who can list and ship. */
    public Seller seller() {
        AuthPrincipal user = principal(user("seller"), Set.of(RoleCode.BUYER, RoleCode.SELLER));
        long profileId = dsl.insertInto(SELLER_PROFILE)
                .set(SELLER_PROFILE.USER_ID, user.userId())
                .set(SELLER_PROFILE.STATUS, "VERIFIED")
                .set(SELLER_PROFILE.VERIFIED_AT, OffsetDateTime.now())
                .returningResult(SELLER_PROFILE.ID)
                .fetchSingle(SELLER_PROFILE.ID);
        return new Seller(user.userId(), profileId, user);
    }

    private long user(String prefix) {
        String name = prefix + "_" + tag();
        return dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, name + "@example.com")
                .set(USER_ACCOUNT.PASSWORD_HASH, "x")
                .set(USER_ACCOUNT.USERNAME, name)
                .set(USER_ACCOUNT.DISPLAY_NAME, name)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);
    }

    private AuthPrincipal principal(long userId, Set<RoleCode> roles) {
        String username = dsl.select(USER_ACCOUNT.USERNAME).from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId)).fetchSingle(USER_ACCOUNT.USERNAME);
        return new AuthPrincipal(userId, username + "@example.com", username, roles);
    }

    /** A real signed access token for this person, for requests that go through the security filters. */
    public String bearer(AuthPrincipal who) {
        AuthUser user = new AuthUser(who.userId(), who.email(), who.username(), who.username(), "x",
                null, null, null, UserStatus.ACTIVE, (short) 0, null, null, OffsetDateTime.now(), who.roles());
        return "Bearer " + jwt.issue(user).value();
    }

    public long address(AuthPrincipal owner) {
        return addresses.create(owner.userId(), new AddressFields("Home", "Somchai Jaidee", "0812345678",
                "99/1 Sukhumvit Rd", null, "Khlong Toei", "Khlong Toei", "Bangkok", "10110", "TH",
                true, true)).id();
    }

    // ---------- catalogue ----------

    public record Card(short gameId, long productId, long variantId, String name, String rarityCode) {
    }

    public short game(String name) {
        String tag = tag();
        return dsl.insertInto(GAME)
                .set(GAME.CODE, "G" + tag.toUpperCase())
                .set(GAME.NAME, name + " " + tag)
                .set(GAME.SLUG, "game-" + tag)
                .returningResult(GAME.ID)
                .fetchSingle(GAME.ID);
    }

    /** A single card with one printing, in a category of its own. */
    public Card card(short gameId, String name, String rarityCode) {
        String tag = tag();
        int categoryId = dsl.insertInto(CATALOG_CATEGORY)
                .set(CATALOG_CATEGORY.GAME_ID, gameId)
                .set(CATALOG_CATEGORY.CODE, "SINGLES_" + tag)
                .set(CATALOG_CATEGORY.NAME, "Singles")
                .set(CATALOG_CATEGORY.SLUG, "singles-" + tag)
                .returningResult(CATALOG_CATEGORY.ID)
                .fetchSingle(CATALOG_CATEGORY.ID);

        long productId = dsl.insertInto(CATALOG_PRODUCT)
                .set(CATALOG_PRODUCT.GAME_ID, gameId)
                .set(CATALOG_PRODUCT.CATEGORY_ID, categoryId)
                .set(CATALOG_PRODUCT.NAME, name)
                .set(CATALOG_PRODUCT.SLUG, "card-" + tag)
                .set(CATALOG_PRODUCT.RARITY_CODE, rarityCode)
                .returningResult(CATALOG_PRODUCT.ID)
                .fetchSingle(CATALOG_PRODUCT.ID);

        long variantId = dsl.insertInto(CATALOG_VARIANT)
                .set(CATALOG_VARIANT.CATALOG_PRODUCT_ID, productId)
                .set(CATALOG_VARIANT.SKU, "SKU-" + tag)
                .set(CATALOG_VARIANT.LANGUAGE_CODE, "EN")
                .set(CATALOG_VARIANT.FINISH, "NORMAL")
                .returningResult(CATALOG_VARIANT.ID)
                .fetchSingle(CATALOG_VARIANT.ID);

        return new Card(gameId, productId, variantId, name, rarityCode);
    }

    // ---------- stock ----------

    public record Listing(long id, List<ListingUnitResponse> units) {
    }

    /** An ACTIVE listing with {@code cards} cards on it at a fixed price. */
    public Listing onSale(Seller seller, Card card, CardCondition condition, int cards, String price) {
        SellerListingResponse created = listings.create(seller.userId(),
                new MarketKey(card.variantId(), condition),
                new Details(null, null, null, null),
                new Pricing(PricingMode.MANUAL, new BigDecimal(price), null, null, null),
                List.of());
        List<ListingUnitResponse> stocked = units.stockIn(seller.userId(),
                new StockIn(null, null, created.id(), cards, new BigDecimal("50.00"), null, null, null));
        listings.changeStatus(seller.userId(), created.id(), ListingStatus.ACTIVE);
        return new Listing(created.id(), stocked);
    }

    /** Writes off every card, which the quantity trigger turns into SOLD_OUT. */
    public void sellOut(Seller seller, Listing listing) {
        listing.units().forEach(unit -> units.writeOff(seller.userId(), unit.publicUid(), "damaged in test"));
    }

    // ---------- settings ----------

    public void setting(String key, String value) {
        dsl.update(PLATFORM_SETTING)
                .set(PLATFORM_SETTING.SETTING_VALUE, JSONB.valueOf(value))
                .where(PLATFORM_SETTING.SETTING_KEY.eq(key))
                .execute();
    }

    public void restoreDefaultSettings() {
        DEFAULT_SETTINGS.forEach(this::setting);
    }
}
