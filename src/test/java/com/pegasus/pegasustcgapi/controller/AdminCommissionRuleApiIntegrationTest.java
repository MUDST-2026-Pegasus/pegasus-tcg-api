package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.CommissionRule.COMMISSION_RULE;
import static com.pegasus.pegasustcgapi.jooq.tables.SellerOrder.SELLER_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.dto.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.CommissionRuleRequest;
import com.pegasus.pegasustcgapi.model.CardCondition;
import com.pegasus.pegasustcgapi.service.CartService;
import com.pegasus.pegasustcgapi.service.CheckoutService;
import com.pegasus.pegasustcgapi.service.CommissionRuleService;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class AdminCommissionRuleApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired CommissionRuleService rules;
    @Autowired CartService cart;
    @Autowired CheckoutService checkout;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void deletingAUsedRuleKeepsTheChargeFrozenOnTheOrder() throws Exception {
        var seller = data.seller();
        var buyer = data.buyer();
        long adminId = data.admin().userId();
        long ruleId = sellerRule(seller.profileId(), adminId, "7");
        var card = data.card(data.game("Pokemon"), "Commission delete " + data.tag(), "AR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "100.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        long sellerOrderId = checkout.checkout(buyer, "commission-delete-" + data.tag(), null, null)
                .sellerOrders().getFirst().id();
        assertThat(commissionOf(sellerOrderId)).isEqualByComparingTo("7.00");

        mvc.perform(delete("/api/v1/admin/commission-rules/{id}", ruleId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(buyer.userId())))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/commission-rules/{id}", ruleId).with(admin(adminId)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/commission-rules/{id}", ruleId).with(admin(adminId)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/admin/commission-rules/{id}", ruleId).with(admin(adminId)))
                .andExpect(status().isNotFound());
        assertThat(commissionOf(sellerOrderId)).isEqualByComparingTo("7.00");
    }

    @Test
    void deactivateKeepsTheRuleForHistory() throws Exception {
        var seller = data.seller();
        long adminId = data.admin().userId();
        long ruleId = sellerRule(seller.profileId(), adminId, "4");

        mvc.perform(post("/api/v1/admin/commission-rules/{id}/deactivate", ruleId).with(admin(adminId)))
                .andExpect(status().isOk());

        assertThat(dsl.select(COMMISSION_RULE.IS_ACTIVE).from(COMMISSION_RULE)
                .where(COMMISSION_RULE.ID.eq(ruleId)).fetchSingle().value1()).isFalse();
    }

    private BigDecimal commissionOf(long sellerOrderId) {
        return dsl.select(SELLER_ORDER.COMMISSION_AMOUNT).from(SELLER_ORDER)
                .where(SELLER_ORDER.ID.eq(sellerOrderId)).fetchSingle().value1();
    }

    private long sellerRule(long sellerId, long adminId, String rate) {
        return rules.create(new CommissionRuleRequest("SELLER", null, null, sellerId,
                new BigDecimal(rate), BigDecimal.ZERO, null, null, (short) 0,
                OffsetDateTime.now(clock).minusDays(1), null, true), adminId).id();
    }

    private static RequestPostProcessor admin(long adminId) {
        return jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
