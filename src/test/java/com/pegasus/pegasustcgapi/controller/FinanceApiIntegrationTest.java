package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.Payment.PAYMENT;
import static com.pegasus.pegasustcgapi.jooq.tables.PlatformSetting.PLATFORM_SETTING;
import static com.pegasus.pegasustcgapi.jooq.tables.Refund.REFUND;
import static com.pegasus.pegasustcgapi.jooq.tables.ReturnRequest.RETURN_REQUEST;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.dto.CancelOrderRequest;
import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.ShipOrderRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.repository.PaymentRepository;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import com.pegasus.pegasustcgapi.service.OrderLifecycleService;
import com.pegasus.pegasustcgapi.service.PaymentService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.StoredObject;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class FinanceApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;
    @Autowired OrderLifecycleService orders;
    @Autowired PaymentRepository paymentRows;
    @Autowired PaymentService payments;
    @MockitoBean StorageService storage;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void bankPaymentAndManualRefundAreAccessibleOnlyToTheirActors() throws Exception {
        var seller = data.seller();
        var buyer = data.buyer();
        var outsider = data.buyer();
        long adminId = data.admin().userId();
        var card = data.card(data.game("Pokemon"), "HTTP finance " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "http-checkout-" + data.tag(), null, null);

        mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .header("Idempotency-Key", "http-payment-" + data.tag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"salesOrderId\":" + order.orderId()
                                + ",\"method\":\"BANK_TRANSFER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        long paymentId = paymentRows.activeForOrder(order.orderId()).orElseThrow().getId();

        mvc.perform(get("/api/v1/payments/{id}", paymentId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(outsider.userId())))))
                .andExpect(status().isNotFound());
        paymentRows.attachSlip(paymentId, "payments/http-" + data.tag() + ".png", "BANK-HTTP");
        mvc.perform(post("/api/v1/admin/payments/{id}/verify", paymentId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/payments/{id}/verify", paymentId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        orders.cancelBuyerOrder(buyer, order.orderId(), new CancelOrderRequest("Test refund"));
        long refundId = dsl.select(REFUND.ID).from(REFUND)
                .where(REFUND.SELLER_ORDER_ID.eq(order.sellerOrders().getFirst().id()))
                .fetchSingle(REFUND.ID);
        mvc.perform(put("/api/v1/refunds/{id}/destination", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(outsider.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Test Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/refunds/{id}", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(outsider.userId())))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/refunds/{id}/processed", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferReference\":\"REF-HTTP\"}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/refunds/{id}/destination", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Test Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"123-456-7890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountNumber").value("1234567890"));
        mvc.perform(get("/api/v1/admin/refunds")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + refundId + ")].accountNumber")
                        .value(hasItem("1234567890")));
        mvc.perform(put("/api/v1/refunds/{id}/destination", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Other Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"9876543210\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/refunds/{id}/processing", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/refunds/{id}/processing", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"9876543210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(put("/api/v1/refunds/{id}/destination", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Other Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"9876543210\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/refunds/{id}/processed", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferReference\":\"REF-HTTP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transferReference").value("REF-HTTP"));
        mvc.perform(get("/api/v1/admin/refunds/{id}/transfers", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PROCESSED"))
                .andExpect(jsonPath("$.data[0].accountNumber").value("9876543210"));
        mvc.perform(put("/api/v1/refunds/{id}/destination", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Other Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"9876543210\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void returnAndReviewEndpointsRespondWithJsonSafeViews() throws Exception {
        var seller = data.seller();
        var buyer = data.buyer();
        var card = data.card(data.game("Pokemon"), "HTTP return " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        var order = checkout.checkout(buyer, "http-return-" + data.tag(), null, null);
        long suborderId = order.sellerOrders().getFirst().id();
        long itemId = order.sellerOrders().getFirst().items().getFirst().id();
        var payment = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "http-pay-return-" + data.tag());
        paymentRows.attachSlip(payment.id(), "payments/http-return-" + data.tag() + ".png", "BANK-RETURN");
        payments.verify(data.admin().userId(), payment.id(), true, null);
        orders.shipSellerOrder(seller.principal(), suborderId,
                new ShipOrderRequest("Thailand Post", "HTTP-RETURN", "TH_POST", null, null));

        mvc.perform(post("/api/v1/orders/{id}/returns", suborderId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"DAMAGED\",\"description\":\"Test\","
                                + "\"evidenceKeys\":[],\"items\":[{\"orderItemId\":" + itemId
                                + ",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.evidenceKeys").isArray());
        long returnId = dsl.select(RETURN_REQUEST.ID).from(RETURN_REQUEST)
                .where(RETURN_REQUEST.SELLER_ORDER_ID.eq(suborderId))
                .fetchSingle(RETURN_REQUEST.ID);
        mvc.perform(post("/api/v1/sellers/me/returns/{id}/approve", returnId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(seller.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"PARTIAL_REFUND\",\"refundAmount\":40,"
                                + "\"requireShipBack\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        long refundId = dsl.select(REFUND.ID).from(REFUND)
                .where(REFUND.RETURN_REQUEST_ID.eq(returnId)).fetchSingle(REFUND.ID);
        mvc.perform(put("/api/v1/refunds/{id}/destination", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankName\":\"Test Bank\",\"accountName\":\"Buyer\",\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/refunds/{id}/processing", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(data.admin().userId())))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"1234567890\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/refunds/{id}/processed", refundId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(data.admin().userId())))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferReference\":\"REF-RETURN\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/{id}/reviews", suborderId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"title\":\"Great\",\"comment\":\"Fast response\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(5));
        mvc.perform(get("/api/v1/u/{username}/reviews", seller.principal().username()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviews.length()").value(1))
                .andExpect(jsonPath("$.data.summary.ratingCount").value(1));
    }

    @Test
    void slipAutoApproveSettingTransitionsPaymentDirectlyToSucceeded() throws Exception {
        dsl.update(PLATFORM_SETTING)
                .set(PLATFORM_SETTING.SETTING_VALUE, JSONB.valueOf("true"))
                .where(PLATFORM_SETTING.SETTING_KEY.eq("payment.slip_auto_approve"))
                .execute();
        try {
            var seller = data.seller();
            var buyer = data.buyer();
            var card = data.card(data.game("Pokemon"), "Auto Slip " + data.tag(), "AR");
            var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
            cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
            var order = checkout.checkout(buyer, "auto-slip-chk-" + data.tag(), null, null);

            var pending = payments.create(buyer, order.orderId(), "BANK_TRANSFER", "auto-slip-pay-" + data.tag());
            String slipKey = "payments/auto-" + data.tag() + ".png";
            given(storage.requireUploadedFor(UploadPurpose.PAYMENT_SLIP, slipKey))
                    .willReturn(new StoredObject(slipKey, 1024L, "image/png"));

            mvc.perform(post("/api/v1/payments/{id}/slip", pending.id())
                            .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId()))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slipImageKey\":\"" + slipKey + "\",\"referenceCode\":\"AUTO-REF\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

            assertThat(dsl.select(PAYMENT.STATUS).from(PAYMENT)
                    .where(PAYMENT.ID.eq(pending.id()))
                    .fetchSingle(PAYMENT.STATUS)).isEqualTo("SUCCEEDED");
            assertThat(dsl.select(SELLER_ORDER.STATUS).from(SELLER_ORDER)
                    .where(SELLER_ORDER.SALES_ORDER_ID.eq(order.orderId()))
                    .fetchSingle(SELLER_ORDER.STATUS)).isEqualTo("PAID");
        } finally {
            dsl.update(PLATFORM_SETTING)
                    .set(PLATFORM_SETTING.SETTING_VALUE, JSONB.valueOf("false"))
                    .where(PLATFORM_SETTING.SETTING_KEY.eq("payment.slip_auto_approve"))
                    .execute();
        }
    }
}
