package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;

import com.pegasus.pegasustcgapi.jooq.tables.records.CatalogProductRecord;
import com.pegasus.pegasustcgapi.model.CatalogProduct;
import com.pegasus.pegasustcgapi.model.ProductType;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
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
