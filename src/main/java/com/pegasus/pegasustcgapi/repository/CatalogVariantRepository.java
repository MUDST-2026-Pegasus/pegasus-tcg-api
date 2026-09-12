package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;

import com.pegasus.pegasustcgapi.jooq.tables.records.CatalogVariantRecord;
import com.pegasus.pegasustcgapi.model.CardEdition;
import com.pegasus.pegasustcgapi.model.CardFinish;
import com.pegasus.pegasustcgapi.model.CatalogVariant;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Reads and writes {@code catalog_variant}, the row every listing and price actually points at. */
@Repository
public class CatalogVariantRepository {

    private final DSLContext dsl;

    public CatalogVariantRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<CatalogVariant> findById(long id) {
        return dsl.selectFrom(CATALOG_VARIANT)
                .where(CATALOG_VARIANT.ID.eq(id))
                .fetchOptional()
                .map(CatalogVariantRepository::toVariant);
    }

    public List<CatalogVariant> findByProductId(long productId, boolean includeInactive) {
        Condition visible = includeInactive ? DSL.noCondition() : CATALOG_VARIANT.IS_ACTIVE.isTrue();

        return dsl.selectFrom(CATALOG_VARIANT)
                .where(CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(productId))
                .and(visible)
                .orderBy(CATALOG_VARIANT.LANGUAGE_CODE.asc(), CATALOG_VARIANT.FINISH.asc(),
                        CATALOG_VARIANT.EDITION.asc(), CATALOG_VARIANT.ID.asc())
                .fetch(CatalogVariantRepository::toVariant);
    }

    public boolean skuTaken(String sku, Long exceptId) {
        return dsl.fetchExists(dsl.selectOne().from(CATALOG_VARIANT)
                .where(CATALOG_VARIANT.SKU.equalIgnoreCase(sku))
                .and(exceptId == null ? DSL.noCondition() : CATALOG_VARIANT.ID.ne(exceptId)));
    }

    /**
     * Mirrors {@code ux_catalog_variant_identity}, which is NULLS NOT DISTINCT:
     * two plain prints with no printing note still collide, which is the point.
     *
     * @param exceptId the row being edited, so keeping your own identity is not a clash
     */
    public boolean identityTaken(long productId, String languageCode, CardFinish finish,
            CardEdition edition, String printingNote, Long exceptId) {

        Condition sameNote = printingNote == null
                ? CATALOG_VARIANT.PRINTING_NOTE.isNull()
                : CATALOG_VARIANT.PRINTING_NOTE.eq(printingNote);

        return dsl.fetchExists(dsl.selectOne().from(CATALOG_VARIANT)
                .where(CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(productId))
                .and(CATALOG_VARIANT.LANGUAGE_CODE.eq(languageCode))
                .and(CATALOG_VARIANT.FINISH.eq(finish.name()))
                .and(CATALOG_VARIANT.EDITION.eq(edition.name()))
                .and(sameNote)
                .and(exceptId == null ? DSL.noCondition() : CATALOG_VARIANT.ID.ne(exceptId)));
    }

    public long insert(long productId, VariantFields fields) {
        return dsl.insertInto(CATALOG_VARIANT)
                .set(CATALOG_VARIANT.CATALOG_PRODUCT_ID, productId)
                .set(CATALOG_VARIANT.SKU, fields.sku())
                .set(CATALOG_VARIANT.LANGUAGE_CODE, fields.languageCode())
                .set(CATALOG_VARIANT.FINISH, fields.finish().name())
                .set(CATALOG_VARIANT.EDITION, fields.edition().name())
                .set(CATALOG_VARIANT.PRINTING_NOTE, fields.printingNote())
                .set(CATALOG_VARIANT.BARCODE, fields.barcode())
                .set(CATALOG_VARIANT.IMAGE_URL, fields.imageUrl())
                .set(CATALOG_VARIANT.IS_ACTIVE, fields.active())
                .returningResult(CATALOG_VARIANT.ID)
                .fetchSingle(CATALOG_VARIANT.ID);
    }

    /** The product a variant belongs to never changes; that would be a different card. */
    public boolean update(long id, VariantFields fields) {
        return dsl.update(CATALOG_VARIANT)
                .set(CATALOG_VARIANT.SKU, fields.sku())
                .set(CATALOG_VARIANT.LANGUAGE_CODE, fields.languageCode())
                .set(CATALOG_VARIANT.FINISH, fields.finish().name())
                .set(CATALOG_VARIANT.EDITION, fields.edition().name())
                .set(CATALOG_VARIANT.PRINTING_NOTE, fields.printingNote())
                .set(CATALOG_VARIANT.BARCODE, fields.barcode())
                .set(CATALOG_VARIANT.IMAGE_URL, fields.imageUrl())
                .set(CATALOG_VARIANT.IS_ACTIVE, fields.active())
                .where(CATALOG_VARIANT.ID.eq(id))
                .execute() > 0;
    }

    private static CatalogVariant toVariant(CatalogVariantRecord r) {
        return new CatalogVariant(
                r.getId(),
                r.getCatalogProductId(),
                r.getSku(),
                r.getLanguageCode(),
                CardFinish.valueOf(r.getFinish()),
                CardEdition.valueOf(r.getEdition()),
                r.getPrintingNote(),
                r.getBarcode(),
                r.getImageUrl(),
                r.getIsActive(),
                r.getCreatedAt());
    }

    public record VariantFields(
            String sku,
            String languageCode,
            CardFinish finish,
            CardEdition edition,
            String printingNote,
            String barcode,
            String imageUrl,
            boolean active) {
    }
}
