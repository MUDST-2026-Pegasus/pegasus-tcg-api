package com.pegasus.pegasustcgapi.controller;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.csv.CsvExport;
import com.pegasus.pegasustcgapi.dto.cart.CartItemRequest;
import com.pegasus.pegasustcgapi.dto.order.CheckoutRequest;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.model.user.RoleCode;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.user.RoleRepository;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.cart.CartService;
import com.pegasus.pegasustcgapi.service.order.CheckoutService;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.BinaryResponse;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * GET /admin/orders/export and /admin/users/export (TCG-747). Other tests share the
 * database, so every call is narrowed to this test's own rows with a unique search.
 */
@DisplayName("Admin CSV export (TCG-747)")
class AdminCsvExportIntegrationTest extends PostgresIntegrationTest {

    private static final String ORDERS = "/api/v1/admin/orders/export";
    private static final String USERS = "/api/v1/admin/users/export";
    /** Bangkok midnight to midnight; the + is sent encoded. */
    private static final String ALL_TIME = "from=2000-01-01T00:00:00%2B07:00&to=2100-01-01T00:00:00%2B07:00";

    @Autowired CheckoutService checkout;
    @Autowired CartService cart;
    @Autowired RoleRepository roles;

    private ApiClient client;
    private String adminBearer;
    private String tag;

    @BeforeEach
    void setUp() {
        client = new ApiClient(port);
        adminBearer = data.bearer(data.admin());
        tag = data.tag();
    }

    // ── access ───────────────────────────────────────────────────

    @Test
    @DisplayName("anonymous gets 401; buyer and seller get 403 and no file")
    void onlyAdminsMayExport() {
        String buyer = data.bearer(data.buyer());
        String seller = data.bearer(data.seller().principal());

        for (String path : List.of(ORDERS + "?" + ALL_TIME, USERS)) {
            assertThat(client.get(path, null).status()).isEqualTo(401);
            for (String bearer : List.of(buyer, seller)) {
                Response response = client.get(path, bearer);
                assertThat(response.status()).isEqualTo(403);
                assertThat(response.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED.name());
                assertThat(response.header("Content-Disposition")).isEmpty();
            }
        }
    }

    // ── orders ───────────────────────────────────────────────────

    @Test
    @DisplayName("orders export matches the list for the same filters, one row per sub-order")
    void ordersMatchTheList() {
        AuthPrincipal buyer = placeOrder();
        placeOrderFor(buyer);
        String filters = ALL_TIME + "&q=" + buyer.username();

        Response list = client.get("/api/v1/admin/orders?" + filters, adminBearer);
        BinaryResponse file = client.getBytes(ORDERS + "?" + filters, adminBearer, "*/*");

        assertThat(file.status()).isEqualTo(200);
        assertThat(file.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/csv"));
        assertThat(file.header("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).contains("attachment").contains("admin-orders-2000-01-01-2099-12-31.csv"));
        List<String> lines = lines(file);
        assertThat(lines.getFirst()).isEqualTo("\"seller_order_number\",\"order_number\",\"status\",\"seller\","
                + "\"buyer\",\"item_count\",\"grand_total\",\"created_at\",\"paid_at\",\"shipped_at\","
                + "\"completed_at\",\"cancelled_at\",\"tracking_number\"");
        assertThat(lines).hasSize(1 + list.data().path("totalItems").asInt());
        assertThat(lines.subList(1, lines.size())).hasSize(2).allSatisfy(line -> {
            assertThat(line).contains("\"PENDING_PAYMENT\"").contains("\"" + buyer.username() + "\"");
        });
    }

    @Test
    @DisplayName("orders export filters by status")
    void ordersFilterByStatus() {
        AuthPrincipal buyer = placeOrder();
        String filters = ALL_TIME + "&q=" + buyer.username();

        assertThat(lines(client.getBytes(ORDERS + "?" + filters + "&status=COMPLETED", adminBearer, "*/*")))
                .hasSize(1);
        assertThat(lines(client.getBytes(ORDERS + "?" + filters + "&status=PAID,PENDING_PAYMENT", adminBearer,
                "*/*"))).hasSize(2);
    }

    @Test
    @DisplayName("orders export needs from and to, with to after from")
    void ordersNeedADateRange() {
        assertThat(client.get(ORDERS + "?to=2100-01-01T00:00:00Z", adminBearer).status()).isEqualTo(400);
        assertThat(client.get(ORDERS + "?from=2000-01-01T00:00:00Z", adminBearer).status()).isEqualTo(400);
        assertThat(client.get(ORDERS + "?from=2100-01-01T00:00:00Z&to=2000-01-01T00:00:00Z", adminBearer)
                .status()).isEqualTo(400);
    }

    // ── users ────────────────────────────────────────────────────

    @Test
    @DisplayName("users export matches the list and leaves out email, phone and the rest")
    void usersMatchTheListWithoutPersonalData() {
        long seller = account("exp" + tag + "_seller", "Shop Owner", "0812345678");
        long buyer = account("exp" + tag + "_buyer", "ผู้ซื้อ", null);
        roles.grant(seller, roleId(RoleCode.SELLER), null);
        roles.grant(seller, roleId(RoleCode.BUYER), null);
        roles.grant(buyer, roleId(RoleCode.BUYER), null);

        Response list = client.get("/api/v1/admin/users?q=exp" + tag + "_", adminBearer);
        BinaryResponse file = client.getBytes(USERS + "?q=exp" + tag + "_", adminBearer, "*/*");

        assertThat(file.status()).isEqualTo(200);
        assertThat(file.header("Content-Disposition")).hasValueSatisfying(disposition ->
                assertThat(disposition).matches(".*admin-users-\\d{4}-\\d{2}-\\d{2}\\.csv.*"));
        List<String> lines = lines(file);
        assertThat(lines.getFirst()).isEqualTo(
                "\"user_id\",\"username\",\"display_name\",\"masked_email\",\"roles\",\"status\",\"created_at\"");
        assertThat(lines).hasSize(1 + list.data().path("totalItems").asInt()).hasSize(3);

        String sellerLine = lines.stream().filter(l -> l.startsWith("\"" + seller + "\"")).findFirst().orElseThrow();
        assertThat(sellerLine).contains("\"e***@example.com\"").contains("\"BUYER;SELLER\"").contains("\"ACTIVE\"");
        String all = String.join("\n", lines);
        assertThat(all).doesNotContain("exp" + tag + "_seller@example.com").doesNotContain("0812345678");
        assertThat(all).contains("\"ผู้ซื้อ\"");
    }

    @Test
    @DisplayName("users export filters by role and status")
    void usersFilterByRoleAndStatus() {
        long seller = account("exr" + tag + "_s", "S", null);
        long suspended = account("exr" + tag + "_b", "B", null);
        roles.grant(seller, roleId(RoleCode.SELLER), null);
        dsl.update(USER_ACCOUNT).set(USER_ACCOUNT.STATUS, UserStatus.SUSPENDED)
                .where(USER_ACCOUNT.ID.eq(suspended)).execute();

        String q = "?q=exr" + tag + "_";
        assertThat(lines(client.getBytes(USERS + q + "&role=SELLER", adminBearer, "*/*")))
                .hasSize(2).last().asString().startsWith("\"" + seller + "\"");
        assertThat(lines(client.getBytes(USERS + q + "&status=SUSPENDED", adminBearer, "*/*")))
                .hasSize(2).last().asString().startsWith("\"" + suspended + "\"");
    }

    @Test
    @DisplayName("a display name like =1+1 comes out as '=1+1")
    void formulasAreDefused() {
        long id = account("exf" + tag + "_x", "=1+1", null);

        List<String> lines = lines(client.getBytes(USERS + "?q=exf" + tag + "_", adminBearer, "*/*"));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("\"" + id + "\",").contains(",\"'=1+1\",");
    }

    @Test
    @DisplayName("more than 20,000 matching rows is a 400 EXPORT_TOO_LARGE, not a cut-off file")
    void tooManyRows() {
        String prefix = "bulk" + tag + "_";
        dsl.execute("INSERT INTO user_account (email, username, display_name) "
                + "SELECT {0} || g || '@example.com', {0} || g, 'Bulk' FROM generate_series(1, {1}) g",
                DSL.val(prefix), DSL.val(CsvExport.MAX_ROWS + 1));
        try {
            Response response = client.get(USERS + "?q=" + prefix, adminBearer);

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.errorCode()).isEqualTo(ErrorCode.EXPORT_TOO_LARGE.name());
            assertThat(response.header("Content-Disposition")).isEmpty();
        } finally {
            dsl.deleteFrom(USER_ACCOUNT).where(USER_ACCOUNT.USERNAME.startsWith(prefix)).execute();
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private AuthPrincipal placeOrder() {
        AuthPrincipal buyer = data.buyer();
        placeOrderFor(buyer);
        return buyer;
    }

    private void placeOrderFor(AuthPrincipal buyer) {
        Seller seller = data.seller();
        var card = data.card(data.game("Pokemon"), "Export " + data.tag(), "SAR");
        var listing = data.onSale(seller, card, CardCondition.NM, 1, "120.00");
        cart.addItem(buyer, null, new CartItemRequest(listing.id(), 1));
        checkout.checkout(buyer, "export-" + data.tag(), null,
                new CheckoutRequest(data.address(buyer), null));
    }

    private long account(String username, String displayName, String phone) {
        return dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, username + "@example.com")
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
                .set(USER_ACCOUNT.PHONE, phone)
                .returningResult(USER_ACCOUNT.ID)
                .fetchSingle(USER_ACCOUNT.ID);
    }

    private short roleId(RoleCode code) {
        return roles.findIdByCode(code).orElseThrow();
    }

    /** The file's lines without the BOM; checks the BOM and CRLF on the way. */
    private static List<String> lines(BinaryResponse file) {
        assertThat(file.status()).isEqualTo(200);
        String text = new String(file.body(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(text.substring(1).split("\r\n"));
    }
}
