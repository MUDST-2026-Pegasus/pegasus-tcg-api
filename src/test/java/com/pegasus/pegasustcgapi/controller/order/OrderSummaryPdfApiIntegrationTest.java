package com.pegasus.pegasustcgapi.controller.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.BinaryResponse;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Card;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.io.IOException;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The three secured download routes through the real HTTP and authorization stack. */
class OrderSummaryPdfApiIntegrationTest extends PostgresIntegrationTest {

    private static final String PDF = "application/pdf";
    private static final long MISSING_ID = 9_999_999_999L;

    @Autowired CartService cart;

    private ApiClient api;
    private String buyerToken;
    private String sellerOneToken;
    private String sellerTwoToken;
    private String adminToken;
    private long salesOrderId;
    private long sellerOneOrderId;
    private String orderNumber;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        var buyer = data.buyer();
        Seller sellerOne = data.seller();
        Seller sellerTwo = data.seller();
        buyerToken = data.bearer(buyer);
        sellerOneToken = data.bearer(sellerOne.principal());
        sellerTwoToken = data.bearer(sellerTwo.principal());
        adminToken = data.bearer(data.admin());

        Card firstCard = data.card(data.game("PDF order"), "PDF seller one card", "SR");
        Card secondCard = data.card(data.game("PDF order"), "PDF seller two card", "R");
        long firstListing = data.onSale(sellerOne, firstCard, CardCondition.NM, 2, "100.00").id();
        long secondListing = data.onSale(sellerTwo, secondCard, CardCondition.LP, 2, "80.00").id();
        cart.addItem(buyer, null, new CartItemRequest(firstListing, 1));
        cart.addItem(buyer, null, new CartItemRequest(secondListing, 1));

        Response checkout = api.post(ApiPaths.CHECKOUT, buyerToken, Map.of(),
                Map.of("Idempotency-Key", "order-summary-pdf-" + data.tag()));
        assertThat(checkout.status()).as(checkout.body().toString()).isEqualTo(201);
        salesOrderId = checkout.data().path("orderId").asLong();
        orderNumber = checkout.data().path("orderNumber").asString();
        sellerOneOrderId = subOrderId(checkout, sellerOne.profileId());
    }

    @Test
    void buyerSellerAndAdminReceiveValidRoleSpecificPdfDownloads() throws Exception {
        BinaryResponse buyerPdf = api.getBytes(ApiPaths.ORDERS + "/" + salesOrderId + "/summary.pdf", buyerToken, PDF);
        BinaryResponse sellerPdf = api.getBytes(
                ApiPaths.SELLERS_ME_ORDERS + "/" + sellerOneOrderId + "/summary.pdf", sellerOneToken, PDF);
        BinaryResponse adminPdf = api.getBytes(
                ApiPaths.ADMIN + "/orders/" + sellerOneOrderId + "/summary.pdf", adminToken, PDF);

        assertPdf(buyerPdf);
        assertPdf(sellerPdf);
        assertPdf(adminPdf);
        assertThat(pdfText(buyerPdf)).contains("Buyer copy").doesNotContain("Platform commission", "0812345678");
        assertThat(pdfText(sellerPdf)).contains("Seller copy", "FULFILLMENT & PAYOUT", "Platform commission");
        assertThat(pdfText(adminPdf)).contains("Internal admin copy", "RISK & AUDIT");
        assertThat(buyerPdf.header("content-disposition").orElse("")).contains("order-summary-" + orderNumber + ".pdf");
    }

    @Test
    void unauthenticatedRequestsToEveryPdfRouteReturn401() {
        assertThat(api.getBytes(ApiPaths.ORDERS + "/" + salesOrderId + "/summary.pdf", null, PDF).status()).isEqualTo(401);
        assertThat(api.getBytes(ApiPaths.SELLERS_ME_ORDERS + "/" + sellerOneOrderId + "/summary.pdf", null, PDF).status())
                .isEqualTo(401);
        assertThat(api.getBytes(ApiPaths.ADMIN + "/orders/" + sellerOneOrderId + "/summary.pdf", null, PDF).status())
                .isEqualTo(401);
    }

    @Test
    void crossBuyerAndCrossSellerDownloadsAreHiddenAsNotFoundAndAdminRequiresAdminRole() {
        String otherBuyerToken = data.bearer(data.buyer());
        assertThat(api.getBytes(ApiPaths.ORDERS + "/" + salesOrderId + "/summary.pdf", otherBuyerToken, PDF).status())
                .isEqualTo(404);
        assertThat(api.getBytes(
                ApiPaths.SELLERS_ME_ORDERS + "/" + sellerOneOrderId + "/summary.pdf", sellerTwoToken, PDF).status())
                .isEqualTo(404);
        assertThat(api.getBytes(
                ApiPaths.ADMIN + "/orders/" + sellerOneOrderId + "/summary.pdf", buyerToken, PDF).status())
                .isEqualTo(403);
    }

    @Test
    void missingBuyerSalesOrderAndSellerSubOrderReturn404ForTheirAuthorizedRoutes() {
        assertThat(api.getBytes(ApiPaths.ORDERS + "/" + MISSING_ID + "/summary.pdf", buyerToken, PDF).status())
                .isEqualTo(404);
        assertThat(api.getBytes(ApiPaths.SELLERS_ME_ORDERS + "/" + MISSING_ID + "/summary.pdf", sellerOneToken, PDF).status())
                .isEqualTo(404);
        assertThat(api.getBytes(ApiPaths.ADMIN + "/orders/" + MISSING_ID + "/summary.pdf", adminToken, PDF).status())
                .isEqualTo(404);
    }

    private static long subOrderId(Response checkout, long sellerProfileId) {
        for (var subOrder : checkout.data().path("sellerOrders")) {
            if (subOrder.path("sellerProfileId").asLong() == sellerProfileId) {
                return subOrder.path("id").asLong();
            }
        }
        throw new AssertionError("Checkout response did not include seller profile " + sellerProfileId);
    }

    private static void assertPdf(BinaryResponse response) {
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("content-type").orElse("")).startsWith(PDF);
        assertThat(response.header("cache-control").orElse("")).contains("no-store");
        assertThat(new String(response.body(), 0, 5)).isEqualTo("%PDF-");
    }

    private static String pdfText(BinaryResponse response) throws IOException {
        try (var document = Loader.loadPDF(response.body())) {
            return new PDFTextStripper().getText(document);
        }
    }
}
