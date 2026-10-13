package com.pegasus.pegasustcgapi.repository;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.CollectionItem.COLLECTION_ITEM;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.ProductCollectorResponse;
import com.pegasus.pegasustcgapi.model.UserStatus;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Only explicitly public, non-deleted cards belonging to active accounts are discoverable. */
@Repository
public class ProductCollectorRepository {

    private final DSLContext dsl;

    public ProductCollectorRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public PageResponse<ProductCollectorResponse> findByProduct(long productId, int page, int size) {
        Paging paging = Paging.of(page, size);
        Condition visible = COLLECTION_ITEM.IS_PUBLIC.isTrue()
                .and(COLLECTION_ITEM.DELETED_AT.isNull())
                .and(CATALOG_VARIANT.CATALOG_PRODUCT_ID.eq(productId))
                .and(USER_ACCOUNT.STATUS.eq(UserStatus.ACTIVE))
                .and(USER_ACCOUNT.DELETED_AT.isNull());

        var quantity = DSL.sum(COLLECTION_ITEM.QUANTITY);
        List<ProductCollectorResponse> rows = dsl
                .select(USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME, USER_ACCOUNT.AVATAR_URL, quantity)
                .from(COLLECTION_ITEM)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(COLLECTION_ITEM.CATALOG_VARIANT_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(COLLECTION_ITEM.USER_ID))
                .where(visible)
                .groupBy(USER_ACCOUNT.ID, USER_ACCOUNT.USERNAME, USER_ACCOUNT.DISPLAY_NAME,
                        USER_ACCOUNT.AVATAR_URL)
                .orderBy(quantity.desc(), USER_ACCOUNT.USERNAME.asc())
                .limit(paging.size())
                .offset(paging.offset())
                .fetch(record -> new ProductCollectorResponse(
                        record.get(USER_ACCOUNT.USERNAME),
                        record.get(USER_ACCOUNT.DISPLAY_NAME),
                        record.get(USER_ACCOUNT.AVATAR_URL),
                        record.get(quantity).longValue()));

        long total = dsl.select(DSL.countDistinct(USER_ACCOUNT.ID))
                .from(COLLECTION_ITEM)
                .join(CATALOG_VARIANT).on(CATALOG_VARIANT.ID.eq(COLLECTION_ITEM.CATALOG_VARIANT_ID))
                .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(COLLECTION_ITEM.USER_ID))
                .where(visible)
                .fetchSingle()
                .value1();
        return PageResponse.of(rows, paging.page(), paging.size(), total);
    }
}
