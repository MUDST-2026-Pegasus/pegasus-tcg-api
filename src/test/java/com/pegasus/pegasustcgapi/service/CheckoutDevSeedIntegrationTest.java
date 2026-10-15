package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

class CheckoutDevSeedIntegrationTest extends PostgresIntegrationTest {
    @Autowired ShippingOptionService shipping;
    @Autowired AddressService addresses;
    @Autowired com.pegasus.pegasustcgapi.repository.SellerProfileRepository profiles;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void demoSeedsCanBeRepeatedAndProvideDeliveryChoicesAndDefaultAddresses() throws Exception {
        for (String name : new String[] { "dev_seed.sql", "catalog_market_collection_seed.sql",
                "cat03_product_detail_seed.sql", "home_seed.sql" }) {
            String sql = new ClassPathResource("db/" + name).getContentAsString(StandardCharsets.UTF_8).replace("\\set ON_ERROR_STOP on", "")
                    // The test transaction rolls back demo rows and platform settings after assertions.
                    .replaceAll("(?m)^BEGIN;\\s*$", "")
                    .replaceAll("(?m)^COMMIT;\\s*$", "");
            for (int run = 0; run < 2; run++) {
                dsl.connection(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute(sql);
                        // home_seed's ON COMMIT DROP table normally disappears between script runs.
                        if (name.equals("home_seed.sql")) statement.execute("DROP TABLE IF EXISTS pg_temp.seed_order");
                    }
                });
            }
        }
        for (String username : new String[] { "buyer_user", "demo_catalog_collector_a", "collector_mai" }) {
            long userId = dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.USERNAME.eq(username)).fetchSingle(USER_ACCOUNT.ID);
            assertThat(addresses.list(userId)).anyMatch(address -> address.defaultShipping());
        }
        for (String username : new String[] { "seller_user", "demo_catalog_shop_a", "demo_cat03_shop_a", "pegasus_official" }) {
            long userId = dsl.select(USER_ACCOUNT.ID).from(USER_ACCOUNT)
                    .where(USER_ACCOUNT.USERNAME.eq(username)).fetchSingle(USER_ACCOUNT.ID);
            long sellerId = profiles.findByUserId(userId).orElseThrow().id();
            assertThat(shipping.listActive(sellerId)).isNotEmpty();
        }
    }
}
