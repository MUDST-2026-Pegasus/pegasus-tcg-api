package com.pegasus.pegasustcgapi.repository.home;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerProfile.SELLER_PROFILE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerRatingSummary.SELLER_RATING_SUMMARY;
import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class RecommendedShopRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired RecommendedShopRepository shops;

    @Test
    @Transactional
    void filtersAndRanksEligibleShopsWithStableTiesAndBatchPreviews() {
        // Hide fixtures from other integration tests inside this rolled-back transaction.
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "NOT_APPLIED").execute();
        var card = data.card(data.game("Recommended shops"), "Recommendation card", "COMMON");

        Seller manyReviews = data.seller();
        var first = data.onSale(manyReviews, card, CardCondition.NM, 1, "100.00");
        var second = data.onSale(manyReviews, card, CardCondition.LP, 1, "100.00");
        var third = data.onSale(manyReviews, card, CardCondition.MP, 1, "100.00");
        var newest = data.onSale(manyReviews, card, CardCondition.HP, 1, "100.00");
        rating(manyReviews, 100, 480, 0, 0, 0, 20, 80);

        Seller onePerfectReview = data.seller();
        data.onSale(onePerfectReview, card, CardCondition.NM, 1, "100.00");
        rating(onePerfectReview, 1, 5, 0, 0, 0, 0, 1);

        Seller unrated = data.seller();
        data.onSale(unrated, card, CardCondition.NM, 1, "100.00");

        Seller sameCountNewerId = data.seller();
        data.onSale(sameCountNewerId, card, CardCondition.NM, 1, "100.00");

        Seller alsoUnrated = data.seller();
        data.onSale(alsoUnrated, card, CardCondition.NM, 1, "100.00");
        data.onSale(alsoUnrated, card, CardCondition.LP, 1, "100.00");

        Seller twoLowReviews = data.seller();
        data.onSale(twoLowReviews, card, CardCondition.NM, 1, "100.00");
        rating(twoLowReviews, 2, 6, 0, 0, 2, 0, 0);

        Seller poorlyRated = data.seller();
        data.onSale(poorlyRated, card, CardCondition.NM, 1, "100.00");
        rating(poorlyRated, 3, 9, 0, 0, 3, 0, 0);

        Seller unverified = data.seller();
        data.onSale(unverified, card, CardCondition.NM, 1, "100.00");
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "PENDING")
                .where(SELLER_PROFILE.ID.eq(unverified.profileId())).execute();

        Seller inactiveAccount = data.seller();
        data.onSale(inactiveAccount, card, CardCondition.NM, 1, "100.00");
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, UserStatus.SUSPENDED)
                .where(USER_ACCOUNT.ID.eq(inactiveAccount.userId())).execute();

        data.seller(); // Verified but no active listing.

        var ranked = shops.topShops();
        assertThat(ranked).extracting(RecommendedShopRepository.Shop::sellerId)
                .containsExactly(manyReviews.profileId(), onePerfectReview.profileId(),
                        alsoUnrated.profileId(), unrated.profileId(),
                        sameCountNewerId.profileId(), twoLowReviews.profileId());
        assertThat(ranked.get(2).ratingCount()).isZero();
        assertThat(ranked.get(0).activeListingCount()).isEqualTo(4);

        var previews = shops.previews(ranked.stream().map(RecommendedShopRepository.Shop::sellerId).toList());
        assertThat(previews).filteredOn(p -> p.sellerId() == manyReviews.profileId())
                .extracting(RecommendedShopRepository.Preview::listingId)
                .containsExactly(newest.id(), third.id(), second.id());
    }

    @Test
    void endpointIsPublicAndReturnsAtMostEightShops() {
        var response = new ApiClient(port).get("/api/v1/home/recommended-shops", null);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.data().size()).isLessThanOrEqualTo(8);
    }

    @Test
    @Transactional
    void returnsEmptyWhenNoShopQualifies() {
        dsl.update(SELLER_PROFILE).set(SELLER_PROFILE.STATUS, "NOT_APPLIED").execute();

        assertThat(shops.topShops()).isEmpty();
        assertThat(shops.previews(java.util.List.of())).isEmpty();
    }

    private void rating(Seller seller, int count, int sum,
                        int one, int two, int three, int four, int five) {
        dsl.insertInto(SELLER_RATING_SUMMARY)
                .set(SELLER_RATING_SUMMARY.SELLER_PROFILE_ID, seller.profileId())
                .set(SELLER_RATING_SUMMARY.RATING_COUNT, count)
                .set(SELLER_RATING_SUMMARY.RATING_SUM, sum)
                .set(SELLER_RATING_SUMMARY.RATING_AVG,
                        BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count)))
                .set(SELLER_RATING_SUMMARY.COUNT_1, one)
                .set(SELLER_RATING_SUMMARY.COUNT_2, two)
                .set(SELLER_RATING_SUMMARY.COUNT_3, three)
                .set(SELLER_RATING_SUMMARY.COUNT_4, four)
                .set(SELLER_RATING_SUMMARY.COUNT_5, five)
                .execute();
    }
}
