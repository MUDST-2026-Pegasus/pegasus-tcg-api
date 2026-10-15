package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.SalesOrder.SALES_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerShippingOption.SELLER_SHIPPING_OPTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.PublicListingResponse;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository.PublicListingQuery;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PublicListingSellerDeliveryIntegrationTest extends PostgresIntegrationTest {

    @Autowired ListingBrowseService browse;

    @Test
    void sellerAndDeliveryMatchActiveDataOnPageAndDetail() {
        Seller seller = data.seller();
        short game = data.game("Public listing seller delivery");
        var card = data.card(game, "Public listing card", "C");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        dsl.deleteFrom(SELLER_SHIPPING_OPTION)
                .where(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(seller.profileId())).execute();

        PublicListingResponse empty = find(card.productId(), listing.id());
        assertThat(empty.seller().verified()).isTrue();
        assertThat(empty.seller().ratingAvg()).isNull();
        assertThat(empty.seller().ratingCount()).isZero();
        assertThat(empty.seller().salesCount()).isZero();
        assertThat(empty.delivery().optionCount()).isZero();
        assertThat(empty.delivery().freeShipping()).isFalse();
        assertThat(empty.delivery().fastestEstDaysMin()).isNull();
        assertThat(empty.delivery().fastestEstDaysMax()).isNull();

        dsl.insertInto(SELLER_RATING_SUMMARY)
                .set(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_RATING_SUMMARY.RATING_COUNT, 2)
                .set(SELLER_RATING_SUMMARY.RATING_SUM, 9)
                .set(SELLER_RATING_SUMMARY.RATING_AVG, new BigDecimal("4.50"))
                .set(SELLER_RATING_SUMMARY.COUNT_4, 1)
                .set(SELLER_RATING_SUMMARY.COUNT_5, 1).execute();
        shipping(seller, "Slow paid", "50", null, (short) 3, (short) 5, true);
        shipping(seller, "Fast free threshold", "50", "100", (short) 1, (short) 2, true);
        shipping(seller, "Inactive", "0", null, (short) 0, (short) 0, false);
        sale(seller, "COMPLETED");
        sale(seller, "RETURNED");

        PublicListingResponse page = find(card.productId(), listing.id());
        PublicListingResponse detail = browse.get(listing.id()).listing();
        assertThat(page.seller().ratingAvg()).isEqualByComparingTo("4.50");
        assertThat(page.seller().ratingCount()).isEqualTo(2);
        assertThat(page.seller().salesCount()).isEqualTo(1);
        assertThat(page.delivery().optionCount()).isEqualTo(2);
        assertThat(page.delivery().fastestEstDaysMin()).isEqualTo((short) 1);
        assertThat(page.delivery().fastestEstDaysMax()).isEqualTo((short) 2);
        assertThat(page.delivery().freeShipping()).isTrue();
        assertThat(detail.seller()).isEqualTo(page.seller());
        assertThat(detail.delivery()).isEqualTo(page.delivery());

        dsl.update(SELLER_SHIPPING_OPTION).setNull(SELLER_SHIPPING_OPTION.FREE_THRESHOLD)
                .where(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(seller.profileId())).execute();
        assertThat(find(card.productId(), listing.id()).delivery().freeShipping()).isFalse();
        dsl.update(SELLER_SHIPPING_OPTION).set(SELLER_SHIPPING_OPTION.BASE_FEE, BigDecimal.ZERO)
                .where(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID.eq(seller.profileId()))
                .and(SELLER_SHIPPING_OPTION.NAME.eq("Slow paid")).execute();
        assertThat(find(card.productId(), listing.id()).delivery().freeShipping()).isTrue();
    }

    @Test
    void onlyVerifiedSellersArePublicAndPageReadHasFixedQueryCount() {
        Seller seller = data.seller();
        short game = data.game("Public listing query count");
        var card = data.card(game, "Query count card", "C");
        var first = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        for (int i = 0; i < 4; i++) {
            data.onSale(seller, card, CardCondition.NM, 1, "110.00");
        }
        AtomicInteger reads = new AtomicInteger();
        DSLContext counted = DSL.using(dsl.configuration().derive(
                new DefaultExecuteListenerProvider(new DefaultExecuteListener() {
                    @Override public void executeStart(org.jooq.ExecuteContext context) { reads.incrementAndGet(); }
                })));
        ListingRepository repository = new ListingRepository(counted);
        PublicListingQuery one = new PublicListingQuery(null, null, card.productId(), null, null,
                null, null, PublicListingQuery.Sort.PRICE_ASC, 1, 0);
        assertThat(repository.findPublicPage(one)).hasSize(1);
        int oneCount = reads.getAndSet(0);
        PublicListingQuery many = new PublicListingQuery(null, null, card.productId(), null, null,
                null, null, PublicListingQuery.Sort.PRICE_ASC, 20, 0);
        assertThat(repository.findPublicPage(many)).hasSize(5);
        assertThat(reads.get()).isEqualTo(oneCount);

        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "PENDING")
                .where(SELLER_PROFILE.ID.eq(seller.profileId())).execute();
        assertThat(browse.market(null, card.productId(), null, null, null, null, null, 0, 20)
                .items()).extracting(PublicListingResponse::id).doesNotContain(first.id());
    }

    private PublicListingResponse find(long productId, long listingId) {
        return browse.market(null, productId, null, null, null, null, null, 0, 20)
                .items().stream().filter(l -> l.id() == listingId).findFirst().orElseThrow();
    }

    private void shipping(Seller seller, String name, String base, String threshold,
            short min, short max, boolean active) {
        dsl.insertInto(SELLER_SHIPPING_OPTION)
                .set(SELLER_SHIPPING_OPTION.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_SHIPPING_OPTION.NAME, name)
                .set(SELLER_SHIPPING_OPTION.BASE_FEE, new BigDecimal(base))
                .set(SELLER_SHIPPING_OPTION.PER_ITEM_FEE, BigDecimal.ZERO)
                .set(SELLER_SHIPPING_OPTION.FREE_THRESHOLD, threshold == null ? null : new BigDecimal(threshold))
                .set(SELLER_SHIPPING_OPTION.EST_DAYS_MIN, min)
                .set(SELLER_SHIPPING_OPTION.EST_DAYS_MAX, max)
                .set(SELLER_SHIPPING_OPTION.IS_ACTIVE, active).execute();
    }

    private void sale(Seller seller, String status) {
        var buyer = data.buyer();
        long orderId = dsl.insertInto(SALES_ORDER)
                .set(SALES_ORDER.ORDER_NUMBER, "SO-" + data.tag())
                .set(SALES_ORDER.BUYER_ID, buyer.userId())
                .set(SALES_ORDER.STATUS, "COMPLETED")
                .set(SALES_ORDER.CURRENCY, "THB")
                .set(SALES_ORDER.SHIPPING_ADDRESS_SNAPSHOT, JSONB.valueOf("{}"))
                .returningResult(SALES_ORDER.ID).fetchSingle(SALES_ORDER.ID);
        dsl.insertInto(SELLER_ORDER)
                .set(SELLER_ORDER.SALES_ORDER_ID, orderId)
                .set(SELLER_ORDER.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_ORDER.SELLER_ORDER_NUMBER, "SUB-" + data.tag())
                .set(SELLER_ORDER.STATUS, status).execute();
    }
}
