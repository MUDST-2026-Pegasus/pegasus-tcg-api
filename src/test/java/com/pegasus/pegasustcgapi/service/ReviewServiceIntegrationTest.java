package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReviewServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired ReviewService reviews;

    @Test
    void completedOrderCanBeReviewedOnceAndVisibilityRebuildsSummary() {
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "Mew", "SAR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "review-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        assertThatThrownBy(() -> reviews.create(buyer.userId(), suborderId,
                (short) 5, "Great", "Fast shipping"))
                .isInstanceOf(RuntimeException.class);

        // Finance/order completion is covered separately; here the fixture reaches review eligibility.
        dsl.update(SELLER_ORDER).set(SELLER_ORDER.STATUS, "COMPLETED")
                .where(SELLER_ORDER.ID.eq(suborderId)).execute();
        var review = reviews.create(buyer.userId(), suborderId, (short) 5, "Great", "Fast shipping");
        assertThatThrownBy(() -> reviews.create(buyer.userId(), suborderId,
                (short) 5, "Again", "Again")).isInstanceOf(RuntimeException.class);
        var publicView = reviews.publicReviews(seller.principal().username());
        assertThat(publicView.reviews()).hasSize(1);
        assertThat(publicView.summary().getRatingCount()).isEqualTo(1);
        assertThat(publicView.summary().getRatingAvg()).isEqualByComparingTo("5.00");

        reviews.visibility(review.getId(), false);
        assertThat(reviews.publicReviews(seller.principal().username()).reviews()).isEmpty();
        assertThat(reviews.publicReviews(seller.principal().username()).summary().getRatingCount()).isZero();
        assertThat(reviews.adminReviews(seller.principal().username()))
                .singleElement().satisfies(hidden -> {
                    assertThat(hidden.id()).isEqualTo(review.getId());
                    assertThat(hidden.visible()).isFalse();
                });
        reviews.visibility(review.getId(), true);
        assertThat(reviews.adminReviews(seller.principal().username()))
                .singleElement().satisfies(shown -> assertThat(shown.visible()).isTrue());
    }
}
