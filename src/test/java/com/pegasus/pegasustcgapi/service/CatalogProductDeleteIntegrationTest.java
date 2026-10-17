package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogImage.CATALOG_IMAGE;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Deleting a catalogue product against the real foreign keys [TCG-538]. */
@DisplayName("CatalogProductService (integration) — deleting a product")
class CatalogProductDeleteIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private CatalogProductService products;

    @Test
    @DisplayName("a product nobody uses is deleted with its variants and images")
    void unusedProductIsDeleted() {
        Card card = data.card(data.game("Pokemon"), "Pikachu typo", "C");
        dsl.insertInto(CATALOG_IMAGE)
                .set(CATALOG_IMAGE.CATALOG_PRODUCT_ID, card.productId())
                .set(CATALOG_IMAGE.IMAGE_KEY, "catalog/" + data.tag() + ".jpg")
                .set(CATALOG_IMAGE.IS_PRIMARY, true)
                .execute();

        products.delete(card.productId());

        assertThat(dsl.fetchExists(CATALOG_PRODUCT, CATALOG_PRODUCT.ID.eq(card.productId()))).isFalse();
        assertThat(dsl.fetchExists(CATALOG_VARIANT, CATALOG_VARIANT.ID.eq(card.variantId()))).isFalse();
        assertThat(dsl.fetchExists(CATALOG_IMAGE, CATALOG_IMAGE.CATALOG_PRODUCT_ID.eq(card.productId())))
                .isFalse();
    }

    @Test
    @DisplayName("a product a seller has listed is refused and left as it was")
    void listedProductIsRefused() {
        Card card = data.card(data.game("Pokemon"), "Charizard ex", "SAR");
        data.onSale(data.seller(), card, CardCondition.NM, 1, "1500.00");

        assertThatThrownBy(() -> products.delete(card.productId()))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_IN_USE);

        assertThat(dsl.fetchExists(CATALOG_PRODUCT, CATALOG_PRODUCT.ID.eq(card.productId()))).isTrue();
        assertThat(dsl.fetchExists(CATALOG_VARIANT, CATALOG_VARIANT.ID.eq(card.variantId()))).isTrue();
    }
}
