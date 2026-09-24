package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;

import com.pegasus.pegasustcgapi.jooq.tables.records.CatalogProductRecord;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.ProductType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.OrderField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes {@code catalog_product}.
 *
 * <p>{@code attributes} is JSONB holding this game's field values. It is read and
 * written as a map here; deciding whether those values are legal for the game is
 * the service's job, since only it knows the registry.
 */
@Repository
public class CatalogProductRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTE_MAP = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final ObjectMapper json;

    public CatalogProductRepository(DSLContext dsl, ObjectMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    public Optional<CatalogProduct> findById(long id) {
        return dsl.selectFrom(CATALOG_PRODUCT)
                .where(CATALOG_PRODUCT.ID.eq(id))
                .fetchOptional()
                .map(this::toProduct);
    }

    /** The cards behind a page of rows, in one query rather than one per row. */
    public Map<Long, CatalogProduct> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(CATALOG_PRODUCT)
                .where(CATALOG_PRODUCT.ID.in(ids))
                .fetchMap(CATALOG_PRODUCT.ID, this::toProduct);
    }

    /** The slug is what URLs carry, so it is a lookup key in its own right. */
    public Optional<CatalogProduct> findBySlug(String slug) {
        return dsl.selectFrom(CATALOG_PRODUCT)
                .where(CATALOG_PRODUCT.SLUG.eq(slug))
                .fetchOptional()
                .map(this::toProduct);
    }

    public boolean slugTaken(String slug) {
        return dsl.fetchExists(dsl.selectOne().from(CATALOG_PRODUCT)
                .where(CATALOG_PRODUCT.SLUG.eq(slug)));
    }

    /**
     * One page of the browse result, ordered as asked.
     *
     * <p>When a filter or the order reads the market, each product is joined to its
     * offer — cheapest matching listing on sale; a product nobody is selling joins
     * to nothing. That join aggregates every listing on sale, so a plain browse by
     * name or date leaves it out entirely.
     */
    public List<CatalogProduct> search(ProductSearchQuery query) {
        Offers offers = query.needsOffer() || query.sortsByOffer() ? new Offers(query) : null;
        return dsl.select(CATALOG_PRODUCT.fields())
                .from(from(offers))
                .where(conditions(query, offers))
                .orderBy(ordering(query.sort(), offers))
                .limit(query.limit())
                .offset(query.offset())
                .fetch(r -> toProduct(r.into(CATALOG_PRODUCT)));
    }

    /** The total behind that page. Order does not change a count, so only a market filter joins. */
    public long count(ProductSearchQuery query) {
        Offers offers = query.needsOffer() ? new Offers(query) : null;
        return dsl.selectCount()
                .from(from(offers))
                .where(conditions(query, offers))
                .fetchSingle(0, long.class);
    }

    private static Table<?> from(Offers offers) {
        return offers == null
                ? CATALOG_PRODUCT
                : CATALOG_PRODUCT.leftJoin(offers.table).on(offers.productId.eq(CATALOG_PRODUCT.ID));
    }

    /** The per-product market a browse query joins, narrowed to the conditions asked for. */
    private static final class Offers {

        final Table<?> table;
        final Field<Long> productId;
        final Field<BigDecimal> lowestPrice;
        final Field<Integer> listingCount;

        Offers(ProductSearchQuery query) {
            table = ProductMarketRepository.offers(query.conditions());
            productId = table.field(ProductMarketRepository.OFFER_PRODUCT_ID, Long.class);
            lowestPrice = table.field(ProductMarketRepository.OFFER_LOWEST_PRICE, BigDecimal.class);
            listingCount = table.field(ProductMarketRepository.OFFER_LISTING_COUNT, Integer.class);
        }
    }

    /** @param offers present whenever {@link ProductSearchQuery#needsOffer()} is */
    private Condition conditions(ProductSearchQuery query, Offers offers) {
        Condition condition = query.activeOnly() ? CATALOG_PRODUCT.IS_ACTIVE.isTrue() : DSL.noCondition();

        if (!query.gameIds().isEmpty()) {
            condition = condition.and(CATALOG_PRODUCT.GAME_ID.in(query.gameIds()));
        }
        if (query.categoryId() != null) {
            condition = condition.and(CATALOG_PRODUCT.CATEGORY_ID.in(
                    DSL.select(CATALOG_CATEGORY.ID)
                            .from(CATALOG_CATEGORY)
                            .where(CATALOG_CATEGORY.ID.eq(query.categoryId()))
                            .or(CATALOG_CATEGORY.PARENT_ID.eq(query.categoryId()))));
        }
        if (query.cardSetId() != null) {
            condition = condition.and(CATALOG_PRODUCT.CARD_SET_ID.eq(query.cardSetId()));
        }
        if (query.productType() != null) {
            condition = condition.and(CATALOG_PRODUCT.PRODUCT_TYPE.eq(query.productType().name()));
        }
        if (query.nameQuery() != null && !query.nameQuery().isBlank()) {
            condition = condition.and(nameMatches(query.nameQuery()));
        }
        if (!query.attributes().isEmpty()) {
            condition = condition.and(attributesContain(query.attributes()));
        }
        if (query.needsOffer()) {
            condition = condition.and(offers.productId.isNotNull());
        }
        if (query.minPrice() != null) {
            condition = condition.and(offers.lowestPrice.ge(query.minPrice()));
        }
        if (query.maxPrice() != null) {
            condition = condition.and(offers.lowestPrice.le(query.maxPrice()));
        }
        return condition;
    }

    /**
     * Emitted as a real {@code ILIKE} rather than jOOQ's {@code lower(x) like lower(?)},
     * because the trigram indexes are on the columns themselves: wrapping them in
     * {@code lower()} would put the search back on a sequential scan.
     *
     * <p>The card number is matched too, so "OP09-119" typed off the card finds it.
     */
    private static Condition nameMatches(String text) {
        String pattern = "%" + text.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";

        return DSL.condition("{0} ILIKE {1} ESCAPE '\\'", CATALOG_PRODUCT.NAME, DSL.val(pattern))
                .or(DSL.condition("{0} ILIKE {1} ESCAPE '\\'",
                        CATALOG_PRODUCT.NAME_LOCAL, DSL.val(pattern)))
                .or(DSL.condition("{0} ILIKE {1} ESCAPE '\\'",
                        CATALOG_PRODUCT.CARD_NUMBER, DSL.val(pattern)));
    }

    /**
     * Containment, so the whole filter is one indexed test against
     * {@code ix_catalog_product_attributes} however many attributes are asked for.
     *
     * <p>It compares JSON types as well as values, which is why the caller types
     * the map first: {@code {"hp":200}} does not contain {@code {"hp":"200"}}.
     */
    private Condition attributesContain(Map<String, Object> attributes) {
        return DSL.condition("{0} @> {1}",
                CATALOG_PRODUCT.ATTRIBUTES,
                DSL.val(JSONB.valueOf(json.writeValueAsString(attributes))));
    }

    /** @param offers present whenever the sort reads the market */
    private static OrderField<?>[] ordering(ProductSearchQuery.Sort sort, Offers offers) {
        return switch (sort) {
            case PRICE_ASC -> new OrderField<?>[] {
                offers.lowestPrice.asc().nullsLast(), CATALOG_PRODUCT.NAME.asc(), CATALOG_PRODUCT.ID.asc() };
            case PRICE_DESC -> new OrderField<?>[] {
                offers.lowestPrice.desc().nullsLast(), CATALOG_PRODUCT.NAME.asc(), CATALOG_PRODUCT.ID.asc() };
            case POPULAR -> new OrderField<?>[] {
                offers.listingCount.desc().nullsLast(), CATALOG_PRODUCT.CREATED_AT.desc(), CATALOG_PRODUCT.ID.desc() };
            case NEWEST -> new OrderField<?>[] {
                CATALOG_PRODUCT.CREATED_AT.desc(), CATALOG_PRODUCT.ID.desc() };
            case CARD_NUMBER -> new OrderField<?>[] {
                CATALOG_PRODUCT.CARD_NUMBER.asc().nullsLast(), CATALOG_PRODUCT.NAME.asc() };
            case NAME -> new OrderField<?>[] {
                CATALOG_PRODUCT.NAME.asc(), CATALOG_PRODUCT.ID.asc() };
        };
    }

    public long insert(ProductFields fields, Long createdBy) {
        return dsl.insertInto(CATALOG_PRODUCT)
                .set(CATALOG_PRODUCT.GAME_ID, fields.gameId())
                .set(CATALOG_PRODUCT.CATEGORY_ID, fields.categoryId())
                .set(CATALOG_PRODUCT.CARD_SET_ID, fields.cardSetId())
                .set(CATALOG_PRODUCT.PRODUCT_TYPE, fields.productType().name())
                .set(CATALOG_PRODUCT.NAME, fields.name())
                .set(CATALOG_PRODUCT.NAME_LOCAL, fields.nameLocal())
                .set(CATALOG_PRODUCT.SLUG, fields.slug())
                .set(CATALOG_PRODUCT.CARD_NUMBER, fields.cardNumber())
                .set(CATALOG_PRODUCT.RARITY_CODE, fields.rarityCode())
                .set(CATALOG_PRODUCT.DESCRIPTION, fields.description())
                .set(CATALOG_PRODUCT.ATTRIBUTES, toJsonb(fields.attributes()))
                .set(CATALOG_PRODUCT.IS_ACTIVE, fields.active())
                .set(CATALOG_PRODUCT.CREATED_BY, createdBy)
                .returningResult(CATALOG_PRODUCT.ID)
                .fetchSingle(CATALOG_PRODUCT.ID);
    }

    /**
     * The game is fixed at creation: its attribute registry is what the stored
     * values were validated against, and moving the product would strand them.
     * The slug is fixed too, because links point at it.
     */
    public boolean update(long id, ProductFields fields) {
        return dsl.update(CATALOG_PRODUCT)
                .set(CATALOG_PRODUCT.CATEGORY_ID, fields.categoryId())
                .set(CATALOG_PRODUCT.CARD_SET_ID, fields.cardSetId())
                .set(CATALOG_PRODUCT.PRODUCT_TYPE, fields.productType().name())
                .set(CATALOG_PRODUCT.NAME, fields.name())
                .set(CATALOG_PRODUCT.NAME_LOCAL, fields.nameLocal())
                .set(CATALOG_PRODUCT.CARD_NUMBER, fields.cardNumber())
                .set(CATALOG_PRODUCT.RARITY_CODE, fields.rarityCode())
                .set(CATALOG_PRODUCT.DESCRIPTION, fields.description())
                .set(CATALOG_PRODUCT.ATTRIBUTES, toJsonb(fields.attributes()))
                .set(CATALOG_PRODUCT.IS_ACTIVE, fields.active())
                .where(CATALOG_PRODUCT.ID.eq(id))
                .execute() > 0;
    }

    private JSONB toJsonb(Map<String, Object> attributes) {
        return JSONB.valueOf(json.writeValueAsString(attributes == null ? Map.of() : attributes));
    }

    private CatalogProduct toProduct(CatalogProductRecord r) {
        Map<String, Object> attributes = r.getAttributes() == null
                ? Map.of()
                : json.readValue(r.getAttributes().data(), ATTRIBUTE_MAP);

        return new CatalogProduct(
                r.getId(),
                r.getGameId(),
                r.getCategoryId(),
                r.getCardSetId(),
                ProductType.valueOf(r.getProductType()),
                r.getName(),
                r.getNameLocal(),
                r.getSlug(),
                r.getCardNumber(),
                r.getRarityCode(),
                r.getDescription(),
                attributes,
                r.getIsActive(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    /** {@code gameId} and {@code slug} are read on insert and ignored on update. */
    public record ProductFields(
            short gameId,
            int categoryId,
            Integer cardSetId,
            ProductType productType,
            String name,
            String nameLocal,
            String slug,
            String cardNumber,
            String rarityCode,
            String description,
            Map<String, Object> attributes,
            boolean active) {
    }
}
