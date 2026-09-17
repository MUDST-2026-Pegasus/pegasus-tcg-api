package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.CollectionItem.COLLECTION_ITEM;

import com.pegasus.pegasustcgapi.jooq.tables.records.CollectionItemRecord;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.model.CollectionItem;
import com.pegasus.pegasustcgapi.model.CollectionSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record5;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code collection_item}.
 *
 * <p>Every read skips soft-deleted rows, and nothing here deletes for real. That
 * is not tidiness: once purchases start adding cards, the unique index on the
 * source is what stops a retried grant from putting back a card the owner threw
 * away — and it only works if the thrown-away row is still there.
 */
@Repository
public class CollectionItemRepository {

    private final DSLContext dsl;

    public CollectionItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<CollectionItem> findPage(CollectionQuery query) {
        return dsl.selectFrom(COLLECTION_ITEM)
                .where(conditions(query))
                .orderBy(COLLECTION_ITEM.CREATED_AT.desc(), COLLECTION_ITEM.ID.desc())
                .limit(query.limit())
                .offset(query.offset())
                .fetch(CollectionItemRepository::toItem);
    }

    public long count(CollectionQuery query) {
        return dsl.fetchCount(COLLECTION_ITEM, conditions(query));
    }

    /** Scoped to the owner, so another person's id reads as not found rather than forbidden. */
    public Optional<CollectionItem> findOfUser(long itemId, long userId) {
        return dsl.selectFrom(COLLECTION_ITEM)
                .where(COLLECTION_ITEM.ID.eq(itemId))
                .and(COLLECTION_ITEM.USER_ID.eq(userId))
                .and(COLLECTION_ITEM.DELETED_AT.isNull())
                .fetchOptional()
                .map(CollectionItemRepository::toItem);
    }

    /**
     * Whether a photo already belongs to somebody else's card, deleted rows
     * included. A public card page hands out a signed URL, and the key is readable
     * in that URL, so without this anyone could claim another collector's photo.
     */
    public boolean imageKeyUsedByAnother(String imageKey, long userId) {
        return dsl.fetchExists(dsl.selectOne().from(COLLECTION_ITEM)
                .where(COLLECTION_ITEM.IMAGE_KEY.eq(imageKey))
                .and(COLLECTION_ITEM.USER_ID.ne(userId)));
    }

    /** Hand-added cards only; a purchase grant has its own path once orders exist. */
    public long insertManual(long userId, CollectionItemFields fields) {
        return dsl.insertInto(COLLECTION_ITEM)
                .set(COLLECTION_ITEM.USER_ID, userId)
                .set(COLLECTION_ITEM.CATALOG_VARIANT_ID, fields.catalogVariantId())
                .set(COLLECTION_ITEM.CONDITION_CODE, fields.condition().name())
                .set(COLLECTION_ITEM.QUANTITY, fields.quantity())
                .set(COLLECTION_ITEM.SOURCE, CollectionSource.MANUAL.name())
                .set(COLLECTION_ITEM.GRADING_COMPANY, fields.gradingCompany())
                .set(COLLECTION_ITEM.GRADE_VALUE, fields.gradeValue())
                .set(COLLECTION_ITEM.CERT_NUMBER, fields.certNumber())
                .set(COLLECTION_ITEM.ACQUIRED_PRICE, fields.acquiredPrice())
                .set(COLLECTION_ITEM.ACQUIRED_AT, fields.acquiredAt())
                .set(COLLECTION_ITEM.IMAGE_KEY, fields.imageKey())
                .set(COLLECTION_ITEM.PERSONAL_NOTE, fields.personalNote())
                .set(COLLECTION_ITEM.IS_PUBLIC, fields.publicItem())
                .returningResult(COLLECTION_ITEM.ID)
                .fetchSingle(COLLECTION_ITEM.ID);
    }

    /** The source never changes: a hand-added card does not become a purchase, or back. */
    public boolean update(long itemId, long userId, CollectionItemFields fields) {
        return dsl.update(COLLECTION_ITEM)
                .set(COLLECTION_ITEM.CATALOG_VARIANT_ID, fields.catalogVariantId())
                .set(COLLECTION_ITEM.CONDITION_CODE, fields.condition().name())
                .set(COLLECTION_ITEM.QUANTITY, fields.quantity())
                .set(COLLECTION_ITEM.GRADING_COMPANY, fields.gradingCompany())
                .set(COLLECTION_ITEM.GRADE_VALUE, fields.gradeValue())
                .set(COLLECTION_ITEM.CERT_NUMBER, fields.certNumber())
                .set(COLLECTION_ITEM.ACQUIRED_PRICE, fields.acquiredPrice())
                .set(COLLECTION_ITEM.ACQUIRED_AT, fields.acquiredAt())
                .set(COLLECTION_ITEM.IMAGE_KEY, fields.imageKey())
                .set(COLLECTION_ITEM.PERSONAL_NOTE, fields.personalNote())
                .set(COLLECTION_ITEM.IS_PUBLIC, fields.publicItem())
                .where(COLLECTION_ITEM.ID.eq(itemId))
                .and(COLLECTION_ITEM.USER_ID.eq(userId))
                .and(COLLECTION_ITEM.DELETED_AT.isNull())
                .execute() > 0;
    }

    /** @return false when the row was not the owner's, or was already gone. */
    public boolean softDelete(long itemId, long userId, OffsetDateTime at) {
        return dsl.update(COLLECTION_ITEM)
                .set(COLLECTION_ITEM.DELETED_AT, at)
                .where(COLLECTION_ITEM.ID.eq(itemId))
                .and(COLLECTION_ITEM.USER_ID.eq(userId))
                .and(COLLECTION_ITEM.DELETED_AT.isNull())
                .execute() > 0;
    }

    /** One aggregate over the owner's live rows. */
    public Summary summarise(long userId) {
        Record5<Integer, BigDecimal, Integer, BigDecimal, BigDecimal> row = dsl.select(
                        DSL.count(),
                        DSL.coalesce(DSL.sum(COLLECTION_ITEM.QUANTITY), BigDecimal.ZERO),
                        DSL.countDistinct(COLLECTION_ITEM.CATALOG_VARIANT_ID),
                        DSL.coalesce(DSL.sum(COLLECTION_ITEM.QUANTITY)
                                .filterWhere(COLLECTION_ITEM.IS_PUBLIC.isTrue()), BigDecimal.ZERO),
                        DSL.coalesce(DSL.sum(COLLECTION_ITEM.ACQUIRED_PRICE.mul(COLLECTION_ITEM.QUANTITY)),
                                BigDecimal.ZERO))
                .from(COLLECTION_ITEM)
                .where(COLLECTION_ITEM.USER_ID.eq(userId))
                .and(COLLECTION_ITEM.DELETED_AT.isNull())
                .fetchSingle();

        return new Summary(row.value1(), row.value2().longValue(), row.value3(),
                row.value4().longValue(), row.value5());
    }

    private static Condition conditions(CollectionQuery query) {
        Condition condition = COLLECTION_ITEM.USER_ID.eq(query.userId())
                .and(COLLECTION_ITEM.DELETED_AT.isNull());

        if (query.publicItem() != null) {
            condition = condition.and(COLLECTION_ITEM.IS_PUBLIC.eq(query.publicItem()));
        }
        if (query.variantId() != null) {
            condition = condition.and(COLLECTION_ITEM.CATALOG_VARIANT_ID.eq(query.variantId()));
        }
        if (query.gameId() != null) {
            // A semi-join, so the page and the count both stay plain reads of this one
            // table and share this condition unchanged.
            condition = condition.and(COLLECTION_ITEM.CATALOG_VARIANT_ID.in(
                    DSL.select(CATALOG_VARIANT.ID)
                            .from(CATALOG_VARIANT)
                            .join(CATALOG_PRODUCT).on(CATALOG_PRODUCT.ID.eq(CATALOG_VARIANT.CATALOG_PRODUCT_ID))
                            .where(CATALOG_PRODUCT.GAME_ID.eq(query.gameId()))));
        }
        return condition;
    }

    private static CollectionItem toItem(CollectionItemRecord r) {
        return new CollectionItem(
                r.getId(),
                r.getUserId(),
                r.getCatalogVariantId(),
                CardCondition.valueOf(r.getConditionCode()),
                r.getQuantity(),
                CollectionSource.valueOf(r.getSource()),
                r.getGradingCompany(),
                r.getGradeValue(),
                r.getCertNumber(),
                r.getAcquiredPrice(),
                r.getAcquiredAt(),
                r.getImageKey(),
                r.getPersonalNote(),
                r.getIsPublic(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }

    /**
     * @param publicItem null for every card; true for a public collection page,
     *                   which must never see the private ones
     */
    public record CollectionQuery(
            long userId,
            Short gameId,
            Long variantId,
            Boolean publicItem,
            int limit,
            int offset) {
    }

    public record CollectionItemFields(
            long catalogVariantId,
            CardCondition condition,
            int quantity,
            String gradingCompany,
            BigDecimal gradeValue,
            String certNumber,
            BigDecimal acquiredPrice,
            OffsetDateTime acquiredAt,
            String imageKey,
            String personalNote,
            boolean publicItem) {
    }

    /**
     * @param acquiredValue what the cards cost in total, counting only the rows
     *                      where the owner recorded a price
     */
    public record Summary(
            int items,
            long cards,
            int distinctVariants,
            long publicCards,
            BigDecimal acquiredValue) {
    }
}
