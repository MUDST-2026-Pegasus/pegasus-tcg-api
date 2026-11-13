package com.pegasus.pegasustcgapi.repository.cart;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.ListingImage.LISTING_IMAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class CartRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired CartRepository carts;

    @Test
    @Transactional
    @DisplayName("a cart line shows the seller's primary photo, and the catalog art only when there is none")
    void sellerPhotoBeforeCatalogArt() {
        Card card = data.card(data.game("Cart photos"), "Charizard ex", "RR");
        Seller seller = data.seller();
        var withPhotos = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        var withoutPhotos = data.onSale(seller, card, CardCondition.LP, 1, "80.00");
        // Added after the listings: creating one signs the catalog art, and tests have no storage.
        String catalogKey = "catalog/" + data.tag() + ".jpg";
        dsl.insertInto(CATALOG_IMAGE)
                .set(CATALOG_IMAGE.CATALOG_PRODUCT_ID, card.productId())
                .set(CATALOG_IMAGE.IMAGE_KEY, catalogKey)
                .set(CATALOG_IMAGE.IS_PRIMARY, true)
                .execute();
        String primaryKey = "listing/" + data.tag() + "-primary.jpg";
        photo(withPhotos.id(), "listing/" + data.tag() + "-first.jpg", 0, false);
        photo(withPhotos.id(), primaryKey, 1, true);

        var details = carts.findCartListingDetails(List.of(withPhotos.id(), withoutPhotos.id()));

        assertThat(details.get(withPhotos.id()).imageKey()).isEqualTo(primaryKey);
        assertThat(details.get(withoutPhotos.id()).imageKey()).isEqualTo(catalogKey);
    }

    private void photo(long listingId, String key, int sortOrder, boolean primary) {
        dsl.insertInto(LISTING_IMAGE)
                .set(LISTING_IMAGE.LISTING_ID, listingId)
                .set(LISTING_IMAGE.IMAGE_KEY, key)
                .set(LISTING_IMAGE.SORT_ORDER, (short) sortOrder)
                .set(LISTING_IMAGE.IS_PRIMARY, primary)
                .execute();
    }
}
