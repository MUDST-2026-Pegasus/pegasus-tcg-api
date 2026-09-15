package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;

import com.pegasus.pegasustcgapi.jooq.tables.records.CatalogImageRecord;
import com.pegasus.pegasustcgapi.model.CatalogImage;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code catalog_image}. Rows hold an object key, never a public URL. */
@Repository
public class CatalogImageRepository {

    private final DSLContext dsl;

    public CatalogImageRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<CatalogImage> findByProductId(long productId) {
        return dsl.selectFrom(CATALOG_IMAGE)
                .where(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(productId))
                .orderBy(CATALOG_IMAGE.IS_PRIMARY.desc(), CATALOG_IMAGE.SORT_ORDER.asc(),
                        CATALOG_IMAGE.ID.asc())
                .fetch(CatalogImageRepository::toImage);
    }

    public Optional<CatalogImage> findById(long id) {
        return dsl.selectFrom(CATALOG_IMAGE)
                .where(CATALOG_IMAGE.ID.eq(id))
                .fetchOptional()
                .map(CatalogImageRepository::toImage);
    }

    /**
     * The primary image key of each product named, for drawing a page of tiles.
     *
     * <p>One query for the page rather than one per row, and a lookup rather than
     * a join, since joining images to products multiplies the rows a search just
     * finished counting.
     */
    public Map<Long, String> primaryKeysOf(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(CATALOG_IMAGE.CATALOG_PRODUCT_ID, CATALOG_IMAGE.IMAGE_KEY)
                .from(CATALOG_IMAGE)
                .where(CATALOG_IMAGE.CATALOG_PRODUCT_ID.in(productIds))
                .and(CATALOG_IMAGE.IS_PRIMARY.isTrue())
                .fetchMap(CATALOG_IMAGE.CATALOG_PRODUCT_ID, CATALOG_IMAGE.IMAGE_KEY);
    }

    /** Whether any catalogue image, of any product, already holds this key. */
    public boolean keyInUse(String imageKey) {
        return dsl.fetchExists(dsl.selectOne().from(CATALOG_IMAGE)
                .where(CATALOG_IMAGE.IMAGE_KEY.eq(imageKey)));
    }

    public boolean hasAny(long productId) {
        return dsl.fetchExists(dsl.selectOne().from(CATALOG_IMAGE)
                .where(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(productId)));
    }

    public long insert(long productId, ImageFields fields) {
        return dsl.insertInto(CATALOG_IMAGE)
                .set(CATALOG_IMAGE.CATALOG_PRODUCT_ID, productId)
                .set(CATALOG_IMAGE.CATALOG_VARIANT_ID, fields.catalogVariantId())
                .set(CATALOG_IMAGE.IMAGE_KEY, fields.imageKey())
                .set(CATALOG_IMAGE.ALT_TEXT, fields.altText())
                .set(CATALOG_IMAGE.SORT_ORDER, fields.sortOrder())
                .set(CATALOG_IMAGE.IS_PRIMARY, fields.primary())
                .returningResult(CATALOG_IMAGE.ID)
                .fetchSingle(CATALOG_IMAGE.ID);
    }

    /**
     * Clears the flag on every other image of the product.
     *
     * <p>{@code ux_catalog_image_primary} allows one primary per product, so this
     * has to run before the new one is set, not after.
     */
    public void clearPrimary(long productId, Long exceptId) {
        dsl.update(CATALOG_IMAGE)
                .set(CATALOG_IMAGE.IS_PRIMARY, false)
                .where(CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(productId))
                .and(CATALOG_IMAGE.IS_PRIMARY.isTrue())
                .and(exceptId == null ? org.jooq.impl.DSL.noCondition() : CATALOG_IMAGE.ID.ne(exceptId))
                .execute();
    }

    public boolean markPrimary(long id) {
        return dsl.update(CATALOG_IMAGE)
                .set(CATALOG_IMAGE.IS_PRIMARY, true)
                .where(CATALOG_IMAGE.ID.eq(id))
                .execute() > 0;
    }

    /** A real delete: nothing references an image row, and the object goes with it. */
    public boolean delete(long id) {
        return dsl.deleteFrom(CATALOG_IMAGE).where(CATALOG_IMAGE.ID.eq(id)).execute() > 0;
    }

    private static CatalogImage toImage(CatalogImageRecord r) {
        return new CatalogImage(
                r.getId(),
                r.getCatalogProductId(),
                r.getCatalogVariantId(),
                r.getImageKey(),
                r.getAltText(),
                r.getSortOrder(),
                r.getIsPrimary());
    }

    /** @param catalogVariantId null for art shared by every variant of the product. */
    public record ImageFields(
            Long catalogVariantId,
            String imageKey,
            String altText,
            short sortOrder,
            boolean primary) {
    }
}
