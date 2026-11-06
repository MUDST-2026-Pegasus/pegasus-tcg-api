package com.pegasus.pegasustcgapi.service.metrics;

import static com.pegasus.pegasustcgapi.jooq.tables.CatalogCategory.CATALOG_CATEGORY;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogProduct.CATALOG_PRODUCT;
import static com.pegasus.pegasustcgapi.jooq.tables.CatalogVariant.CATALOG_VARIANT;
import static com.pegasus.pegasustcgapi.jooq.tables.Game.GAME;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.BinaryResponse;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import com.pegasus.pegasustcgapi.support.TestData;
import com.pegasus.pegasustcgapi.support.TestData.Seller;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Runs the report's aggregate SQL and HTTP endpoints against the real migrated Postgres schema. */
public class AdminWeeklyStockProfitIntegrationTest extends PostgresIntegrationTest {

    private static final AtomicBoolean STANDARD_AUGUST_2026_SEEDED = new AtomicBoolean(false);

    @Autowired AdminWeeklyStockProfitService reportService;

    /**
     * Seeds the canonical CR15 design dataset for week 2026-08-03..2026-08-09 (and prior week
     * 2026-07-27..2026-08-02) once for the shared Testcontainers Postgres instance.
     */
    public static synchronized void seedStandardAugust2026Dataset(DSLContext dsl, TestData data) {
        if (STANDARD_AUGUST_2026_SEEDED.get()) {
            return;
        }
        Seller seller = data.seller();
        var buyer = data.buyer();

        short pokemonGameId = ensureGame(dsl, "CR15_PKM", "Pokémon", "pokemon");
        short onePieceGameId = ensureGame(dsl, "CR15_OP", "One Piece", "one-piece");
        short otherTcgGameId = ensureGame(dsl, "CR15_OTCG", "Other TCG", "other-tcg");

        long pkmSingleVariant = createVariant(dsl, data, pokemonGameId, "SINGLE_CARD");
        long pkmSealedVariant = createVariant(dsl, data, pokemonGameId, "BOOSTER_BOX");
        long opSingleVariant = createVariant(dsl, data, onePieceGameId, "SINGLE_CARD");
        long opSealedVariant = createVariant(dsl, data, onePieceGameId, "BOOSTER_BOX");
        long otcgSingleVariant = createVariant(dsl, data, otherTcgGameId, "SINGLE_CARD");
        long otcgSealedVariant = createVariant(dsl, data, otherTcgGameId, "BOOSTER_BOX");

        // Clear any earlier listing_stock_history rows before 2026-09-01 so the August 2026 cutoff is exact
        dsl.execute("DELETE FROM listing_stock_history WHERE changed_at < TIMESTAMPTZ '2026-09-01T00:00:00+07:00'");

        // Seed active stock at 2026-08-05T10:00:00+07:00 (and close it at 2026-08-10T00:01:00+07:00 so
        // later weeks such as 2026-08-10..16 or 2100-01-04..10 remain clean)
        OffsetDateTime stockActiveAt = OffsetDateTime.parse("2026-08-05T10:00:00+07:00");
        OffsetDateTime stockClosedAt = OffsetDateTime.parse("2026-08-10T00:01:00+07:00");

        // Pokémon: singleCards = 2464, sealedBoxes = 1000 -> remainingStock = 3464
        createHistoricalListingStock(dsl, seller.profileId(), pkmSingleVariant, 2464, stockActiveAt, stockClosedAt);
        createHistoricalListingStock(dsl, seller.profileId(), pkmSealedVariant, 1000, stockActiveAt, stockClosedAt);

        // One Piece: singleCards = 984, sealedBoxes = 500 -> remainingStock = 1484
        createHistoricalListingStock(dsl, seller.profileId(), opSingleVariant, 984, stockActiveAt, stockClosedAt);
        createHistoricalListingStock(dsl, seller.profileId(), opSealedVariant, 500, stockActiveAt, stockClosedAt);

        // Other TCG: singleCards = 836, sealedBoxes = 400 -> remainingStock = 1236
        createHistoricalListingStock(dsl, seller.profileId(), otcgSingleVariant, 836, stockActiveAt, stockClosedAt);
        createHistoricalListingStock(dsl, seller.profileId(), otcgSealedVariant, 400, stockActiveAt, stockClosedAt);

        // Previous week (2026-07-27..2026-08-02): GMV = 362,500.00, Commission/Profit = 10,875.00
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), pkmSingleVariant,
                "COMPLETED", OffsetDateTime.parse("2026-07-29T12:00:00+07:00"),
                3625, new BigDecimal("100.00"), new BigDecimal("362500.00"), new BigDecimal("10875.00"));

        // Current week (2026-08-03..2026-08-09):
        // 1. Pokémon: soldCount = 790 (500 + 280 + 10), GMV = 239,960.00, Profit = 7,198.80
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), pkmSingleVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-03T10:00:00+07:00"),
                500, new BigDecimal("300.00"), new BigDecimal("150000.00"), new BigDecimal("4500.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), pkmSingleVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-04T11:00:00+07:00"),
                280, new BigDecimal("300.00"), new BigDecimal("84000.00"), new BigDecimal("2520.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), pkmSealedVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-05T14:00:00+07:00"),
                10, new BigDecimal("596.00"), new BigDecimal("5960.00"), new BigDecimal("178.80"));

        // 2. One Piece: soldCount = 416 (400 + 16), GMV = 102,840.00, Profit = 3,085.20
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), opSingleVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-06T12:00:00+07:00"),
                400, new BigDecimal("240.00"), new BigDecimal("96000.00"), new BigDecimal("2880.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), opSealedVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-07T15:00:00+07:00"),
                16, new BigDecimal("427.50"), new BigDecimal("6840.00"), new BigDecimal("205.20"));

        // 3. Other TCG: soldCount = 264 (254 + 10), GMV = 85,700.00, Profit = 2,571.00
        // Includes Sunday 23:59:59+07:00 boundary order!
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), otcgSingleVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-08T16:00:00+07:00"),
                254, new BigDecimal("300.00"), new BigDecimal("76200.00"), new BigDecimal("2286.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), otcgSealedVariant,
                "COMPLETED", OffsetDateTime.parse("2026-08-09T23:59:59+07:00"),
                10, new BigDecimal("950.00"), new BigDecimal("9500.00"), new BigDecimal("285.00"));

        // Non-completed orders in the same week that MUST be excluded (CANCELLED, PENDING_PAYMENT, RETURN_REQUESTED)
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), pkmSingleVariant,
                "CANCELLED", OffsetDateTime.parse("2026-08-04T15:00:00+07:00"),
                50, new BigDecimal("1000.00"), new BigDecimal("50000.00"), new BigDecimal("1500.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), opSingleVariant,
                "PENDING_PAYMENT", OffsetDateTime.parse("2026-08-05T15:00:00+07:00"),
                60, new BigDecimal("1000.00"), new BigDecimal("60000.00"), new BigDecimal("1800.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), otcgSingleVariant,
                "RETURN_REQUESTED", OffsetDateTime.parse("2026-08-06T15:00:00+07:00"),
                70, new BigDecimal("1000.00"), new BigDecimal("70000.00"), new BigDecimal("2100.00"));

        STANDARD_AUGUST_2026_SEEDED.set(true);
    }

    private static short ensureGame(DSLContext dsl, String code, String name, String slug) {
        Short existing = dsl.select(GAME.ID).from(GAME).where(GAME.SLUG.eq(slug)).fetchOne(GAME.ID);
        if (existing != null) {
            dsl.update(GAME).set(GAME.NAME, name).where(GAME.ID.eq(existing)).execute();
            return existing;
        }
        return dsl.insertInto(GAME)
                .set(GAME.CODE, code)
                .set(GAME.NAME, name)
                .set(GAME.SLUG, slug)
                .returningResult(GAME.ID)
                .fetchSingle(GAME.ID);
    }

    private static long createVariant(DSLContext dsl, TestData data, short gameId, String productType) {
        String tag = data.tag();
        int categoryId = dsl.insertInto(CATALOG_CATEGORY)
                .set(CATALOG_CATEGORY.GAME_ID, gameId)
                .set(CATALOG_CATEGORY.CODE, "CAT_" + tag.toUpperCase())
                .set(CATALOG_CATEGORY.NAME, "Category " + tag)
                .set(CATALOG_CATEGORY.SLUG, "cat-" + tag)
                .returningResult(CATALOG_CATEGORY.ID)
                .fetchSingle(CATALOG_CATEGORY.ID);
        long productId = dsl.insertInto(CATALOG_PRODUCT)
                .set(CATALOG_PRODUCT.GAME_ID, gameId)
                .set(CATALOG_PRODUCT.CATEGORY_ID, categoryId)
                .set(CATALOG_PRODUCT.PRODUCT_TYPE, productType)
                .set(CATALOG_PRODUCT.NAME, "Product " + tag)
                .set(CATALOG_PRODUCT.SLUG, "prod-" + tag)
                .set(CATALOG_PRODUCT.RARITY_CODE, "R")
                .returningResult(CATALOG_PRODUCT.ID)
                .fetchSingle(CATALOG_PRODUCT.ID);
        return dsl.insertInto(CATALOG_VARIANT)
                .set(CATALOG_VARIANT.CATALOG_PRODUCT_ID, productId)
                .set(CATALOG_VARIANT.SKU, "SKU-" + tag)
                .set(CATALOG_VARIANT.LANGUAGE_CODE, "EN")
                .set(CATALOG_VARIANT.FINISH, "NORMAL")
                .returningResult(CATALOG_VARIANT.ID)
                .fetchSingle(CATALOG_VARIANT.ID);
    }

    private static void createHistoricalListingStock(
            DSLContext dsl,
            long sellerProfileId,
            long variantId,
            int quantityAvailable,
            OffsetDateTime activeAt,
            OffsetDateTime closedAt) {
        Long listingId = dsl.fetchSingle("""
                INSERT INTO listing (seller_profile_id, catalog_variant_id, condition_code, price,
                                     quantity_total, quantity_reserved, quantity_available, status)
                VALUES (?, ?, 'NM', 100.00, ?, 0, ?, 'ACTIVE')
                RETURNING id
                """, sellerProfileId, variantId, quantityAvailable, quantityAvailable)
                .get(0, Long.class);
        dsl.execute("DELETE FROM listing_stock_history WHERE listing_id = ?", listingId);
        dsl.execute("""
                INSERT INTO listing_stock_history (listing_id, status, quantity_available, deleted_at, changed_at)
                VALUES (?, 'ACTIVE', ?, NULL, CAST(? AS timestamptz)),
                       (?, 'DELISTED', 0, NULL, CAST(? AS timestamptz))
                """, listingId, quantityAvailable, activeAt, listingId, closedAt);
    }

    public static void insertOrderWithItem(
            DSLContext dsl,
            TestData data,
            long buyerId,
            long sellerProfileId,
            long variantId,
            String status,
            OffsetDateTime completedAt,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal commissionAmount) {
        String tag = data.tag();
        String salesStatus = "COMPLETED".equals(status) ? "COMPLETED"
                : ("CANCELLED".equals(status) ? "CANCELLED" : "PENDING_PAYMENT");
        Long salesOrderId = dsl.fetchSingle("""
                INSERT INTO sales_order (order_number, buyer_id, status, items_subtotal, shipping_total,
                                         discount_total, grand_total, shipping_address_snapshot,
                                         placed_at, paid_at, completed_at)
                VALUES (?, ?, ?, ?, 0, 0, ?, '{}'::jsonb,
                        CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))
                RETURNING id
                """, "ORD-CR15-" + tag, buyerId, salesStatus, lineTotal, lineTotal,
                completedAt.minusHours(2), completedAt.minusHours(1), completedAt)
                .get(0, Long.class);

        BigDecimal sellerNet = lineTotal.subtract(commissionAmount);
        Long sellerOrderId = dsl.fetchSingle("""
                INSERT INTO seller_order (sales_order_id, seller_profile_id, seller_order_number, status,
                                          items_subtotal, shipping_fee, discount_amount, grand_total,
                                          commission_rate_percent, commission_amount, seller_net_amount,
                                          completed_at, created_at)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, 3.000, ?, ?,
                        CAST(? AS timestamptz), CAST(? AS timestamptz))
                RETURNING id
                """, salesOrderId, sellerProfileId, "SO-CR15-" + tag, status,
                lineTotal, lineTotal, commissionAmount, sellerNet, completedAt, completedAt.minusHours(2))
                .get(0, Long.class);

        dsl.execute("""
                INSERT INTO order_item (seller_order_id, catalog_variant_id, quantity, unit_price,
                                        line_total, unit_cost_snapshot, product_name_snapshot,
                                        variant_label_snapshot, condition_snapshot)
                VALUES (?, ?, ?, ?, ?, 0, 'CR15 Item', 'EN / Normal', 'NM')
                """, sellerOrderId, variantId, quantity, unitPrice, lineTotal);
    }

    @Test
    void emptyFutureWeekReturnsZeroTotalsAndSevenDailyPoints() {
        var report = reportService.report("2100-01-04", "2100-01-10", null, null);

        assertThat(report.hasData()).isFalse();
        assertThat(report.summary().gmv()).isZero();
        assertThat(report.summary().commission()).isZero();
        assertThat(report.summary().netProfit()).isZero();
        assertThat(report.summary().stockDepleted()).isZero();
        assertThat(report.summary().wowChangePercent()).isEqualByComparingTo("0.0");
        assertThat(report.chartData()).hasSize(7);
        assertThat(report.chartData()).allSatisfy(day -> {
            assertThat(day.singles()).isZero();
            assertThat(day.sealed()).isZero();
        });
    }

    @Test
    void enforcesRbacForUnauthenticatedBuyerSellerAndAdmin() {
        seedStandardAugust2026Dataset(dsl, data);
        ApiClient client = new ApiClient(port);
        String url = "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-08-03&endDate=2026-08-09";
        String exportUrl = "/api/v1/admin/reports/weekly-stock-profit/export?startDate=2026-08-03&endDate=2026-08-09";

        // No token -> 401 Unauthorized
        assertThat(client.get(url, null).status()).isEqualTo(401);
        assertThat(client.get(exportUrl, null).status()).isEqualTo(401);

        // BUYER token -> 403 Forbidden
        String buyerToken = data.bearer(data.buyer());
        assertThat(client.get(url, buyerToken).status()).isEqualTo(403);
        assertThat(client.get(exportUrl, buyerToken).status()).isEqualTo(403);

        // SELLER token -> 403 Forbidden
        String sellerToken = data.bearer(data.seller().principal());
        assertThat(client.get(url, sellerToken).status()).isEqualTo(403);
        assertThat(client.get(exportUrl, sellerToken).status()).isEqualTo(403);

        // ADMIN token -> 200 OK with complete JSON structure
        String adminToken = data.bearer(data.admin());
        Response ok = client.get(url, adminToken);
        assertThat(ok.status()).isEqualTo(200);
        JsonNode payload = ok.data();
        assertThat(payload.has("meta")).isTrue();
        assertThat(payload.has("summary")).isTrue();
        assertThat(payload.has("chartData")).isTrue();
        assertThat(payload.has("gameDistribution")).isTrue();
        assertThat(payload.has("breakdownTable")).isTrue();
        assertThat(payload.has("totals")).isTrue();
        assertThat(payload.path("hasData").asBoolean()).isTrue();
    }

    @Test
    void verifiesDataConsistencyForWeekAugust3To9AgainstDesignTotals() {
        seedStandardAugust2026Dataset(dsl, data);
        ApiClient client = new ApiClient(port);
        String adminToken = data.bearer(data.admin());

        Response resp = client.get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-08-03&endDate=2026-08-09",
                adminToken);
        assertThat(resp.status()).isEqualTo(200);

        JsonNode report = resp.data();
        assertThat(report.path("hasData").asBoolean()).isTrue();
        assertThat(report.path("meta").path("timezone").asText()).isEqualTo("Asia/Bangkok");

        JsonNode summary = report.path("summary");
        JsonNode totals = report.path("totals");

        // Summary & Totals exact match against Design numbers
        assertThat(new BigDecimal(summary.path("weeklyGmv").asText())).isEqualByComparingTo("428500.00");
        assertThat(new BigDecimal(summary.path("gmv").asText())).isEqualByComparingTo("428500.00");
        assertThat(new BigDecimal(summary.path("netProfit").asText())).isEqualByComparingTo("12855.00");
        assertThat(new BigDecimal(summary.path("commissionCollected").asText())).isEqualByComparingTo("12855.00");
        assertThat(summary.path("initialStock").asLong()).isEqualTo(7654L);
        assertThat(summary.path("soldCount").asLong()).isEqualTo(1470L);
        assertThat(summary.path("remainingStock").asLong()).isEqualTo(6184L);
        assertThat(new BigDecimal(summary.path("wowChangePercent").asText())).isEqualByComparingTo("18.2");

        // Sum every row in breakdownTable and assert 100% consistency with totals and summary
        JsonNode breakdownTable = report.path("breakdownTable");
        assertThat(breakdownTable.isArray()).isTrue();
        assertThat(breakdownTable.size()).isEqualTo(3);

        long sumInitial = 0;
        long sumSold = 0;
        long sumRemaining = 0;
        long sumSingles = 0;
        long sumSealed = 0;
        BigDecimal sumGmv = BigDecimal.ZERO;
        BigDecimal sumProfit = BigDecimal.ZERO;

        for (JsonNode row : breakdownTable) {
            long initial = row.path("initialStock").asLong();
            long sold = row.path("soldCount").asLong();
            long remaining = row.path("remainingStock").asLong();
            long singles = row.path("singleCards").asLong();
            long sealed = row.path("sealedBoxes").asLong();
            BigDecimal gmv = new BigDecimal(row.path("gmv").asText());
            BigDecimal profit = new BigDecimal(row.path("netProfit").asText());

            assertThat(remaining).isEqualTo(initial - sold);
            assertThat(singles + sealed).isEqualTo(remaining);

            sumInitial += initial;
            sumSold += sold;
            sumRemaining += remaining;
            sumSingles += singles;
            sumSealed += sealed;
            sumGmv = sumGmv.add(gmv);
            sumProfit = sumProfit.add(profit);
        }

        assertThat(sumInitial).isEqualTo(totals.path("initialStock").asLong()).isEqualTo(7654L);
        assertThat(sumSold).isEqualTo(totals.path("soldCount").asLong()).isEqualTo(1470L);
        assertThat(sumRemaining).isEqualTo(totals.path("remainingStock").asLong()).isEqualTo(6184L);
        assertThat(sumSingles + sumSealed).isEqualTo(6184L);
        assertThat(sumGmv).isEqualByComparingTo(new BigDecimal(totals.path("weeklyGmv").asText()))
                .isEqualByComparingTo("428500.00");
        assertThat(sumProfit).isEqualByComparingTo(new BigDecimal(totals.path("netProfit").asText()))
                .isEqualByComparingTo("12855.00");

        // Donut chart distribution: Pokémon 56%, One Piece 24%, Other TCG 20%
        JsonNode dist = report.path("gameDistribution");
        assertThat(dist.size()).isEqualTo(3);
        assertThat(dist.get(0).path("game").asText()).isEqualTo("Pokémon");
        assertThat(new BigDecimal(dist.get(0).path("percentage").asText())).isEqualByComparingTo("56.00");
        assertThat(dist.get(1).path("game").asText()).isEqualTo("One Piece");
        assertThat(new BigDecimal(dist.get(1).path("percentage").asText())).isEqualByComparingTo("24.00");
        assertThat(dist.get(2).path("game").asText()).isEqualTo("Other TCG");
        assertThat(new BigDecimal(dist.get(2).path("percentage").asText())).isEqualByComparingTo("20.00");
    }

    @Test
    void emptyStateWeekAugust10To16ReturnsHasDataFalseAndZeroes() {
        seedStandardAugust2026Dataset(dsl, data);
        ApiClient client = new ApiClient(port);
        String adminToken = data.bearer(data.admin());

        Response resp = client.get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-08-10&endDate=2026-08-16",
                adminToken);
        assertThat(resp.status()).isEqualTo(200);

        JsonNode report = resp.data();
        assertThat(report.path("hasData").asBoolean()).isFalse();

        JsonNode summary = report.path("summary");
        assertThat(new BigDecimal(summary.path("weeklyGmv").asText())).isZero();
        assertThat(new BigDecimal(summary.path("gmv").asText())).isZero();
        assertThat(new BigDecimal(summary.path("netProfit").asText())).isZero();
        assertThat(new BigDecimal(summary.path("commissionCollected").asText())).isZero();
        assertThat(summary.path("initialStock").asLong()).isZero();
        assertThat(summary.path("soldCount").asLong()).isZero();
        assertThat(summary.path("remainingStock").asLong()).isZero();
        assertThat(new BigDecimal(summary.path("wowChangePercent").asText())).isEqualByComparingTo("0.0");
    }

    @Test
    void bangkokTimezoneBoundarySeparatesSunday235959FromMonday000000InDatabase() {
        Seller seller = data.seller();
        var buyer = data.buyer();
        short gameId = ensureGame(dsl, "CR15_TZ", "Pokémon", "pokemon");
        long variantId = createVariant(dsl, data, gameId, "SINGLE_CARD");

        // Sunday 2025-05-11 23:59:59+07:00 (16:59:59 UTC) -> in week 2025-05-05..2025-05-11
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), variantId,
                "COMPLETED", OffsetDateTime.parse("2025-05-11T23:59:59+07:00"),
                10, new BigDecimal("1000.00"), new BigDecimal("10000.00"), new BigDecimal("300.00"));

        // Monday 2025-05-12 00:00:00+07:00 (17:00:00 UTC on Sunday) -> in week 2025-05-12..2025-05-18
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), variantId,
                "COMPLETED", OffsetDateTime.parse("2025-05-12T00:00:00+07:00"),
                20, new BigDecimal("1000.00"), new BigDecimal("20000.00"), new BigDecimal("600.00"));

        var week1 = reportService.report("2025-05-05", "2025-05-11", null, null);
        var week2 = reportService.report("2025-05-12", "2025-05-18", null, null);

        assertThat(week1.summary().weeklyGmv()).isEqualByComparingTo("10000.00");
        assertThat(week1.summary().netProfit()).isEqualByComparingTo("300.00");
        assertThat(week1.summary().soldCount()).isEqualTo(10L);

        assertThat(week2.summary().weeklyGmv()).isEqualByComparingTo("20000.00");
        assertThat(week2.summary().netProfit()).isEqualByComparingTo("600.00");
        assertThat(week2.summary().soldCount()).isEqualTo(20L);
    }

    @Test
    void validatesQueryParametersAndReturns400BadRequestForInvalidDates() {
        ApiClient client = new ApiClient(port);
        String adminToken = data.bearer(data.admin());

        // startDate > endDate
        assertThat(client.get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-08-10&endDate=2026-08-03",
                adminToken).status()).isEqualTo(400);

        // Malformed ISO 8601 date
        assertThat(client.get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=03/08/2026&endDate=09/08/2026",
                adminToken).status()).isEqualTo(400);

        // Not Monday-to-Sunday
        assertThat(client.get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-08-04&endDate=2026-08-10",
                adminToken).status()).isEqualTo(400);
    }

    @Test
    void exportsCsvAndXlsxWithMatchingHeadersFilenamesAndData() throws IOException {
        seedStandardAugust2026Dataset(dsl, data);
        ApiClient client = new ApiClient(port);
        String adminToken = data.bearer(data.admin());

        // 1. CSV Export
        BinaryResponse csvResp = client.getBytes(
                "/api/v1/admin/reports/weekly-stock-profit/export?startDate=2026-08-03&endDate=2026-08-09&format=csv",
                adminToken, "text/csv");
        assertThat(csvResp.status()).isEqualTo(200);
        assertThat(csvResp.header("content-type").orElse("")).contains("text/csv");
        assertThat(csvResp.header("content-disposition").orElse(""))
                .contains("pegasus_weekly_report_2026_32.csv");

        String csvContent = new String(csvResp.body(), StandardCharsets.UTF_8);
        String[] lines = csvContent.trim().split("\\r?\\n");
        // 1 header + 7 daily rows + 1 summary row + 3 game rows = 12 rows
        assertThat(lines).hasSize(12);
        assertThat(csvContent).contains("\"summary\",\"\",\"\",\"\",\"\",\"428500.00\",\"12855.00\",\"12855.00\",\"6184\",\"1470\",\"18.2\"");
        assertThat(csvContent).contains("\"game\",\"\",\"Pokémon\"");
        assertThat(csvContent).contains("\"game\",\"\",\"One Piece\"");
        assertThat(csvContent).contains("\"game\",\"\",\"Other TCG\"");

        // 2. XLSX Export
        BinaryResponse xlsxResp = client.getBytes(
                "/api/v1/admin/reports/weekly-stock-profit/export?startDate=2026-08-03&endDate=2026-08-09&format=xlsx",
                adminToken, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(xlsxResp.status()).isEqualTo(200);
        assertThat(xlsxResp.header("content-type").orElse(""))
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(xlsxResp.header("content-disposition").orElse(""))
                .contains("pegasus_weekly_report_2026_32.xlsx");

        Map<String, String> entries = unzipEntries(xlsxResp.body());
        assertThat(entries).containsKey("xl/worksheets/sheet1.xml");
        String sheetXml = entries.get("xl/worksheets/sheet1.xml");
        assertThat(sheetXml).contains("<row r=\"1\">").contains("<row r=\"12\">");
        assertThat(sheetXml).contains("428500.00").contains("12855.00").contains("6184").contains("1470");
        assertThat(sheetXml).contains("Pokémon").contains("One Piece").contains("Other TCG");
    }

    @Test
    void otherProductTypesAddUpInTheChartAndTheStockSplit() {
        Seller seller = data.seller();
        var buyer = data.buyer();
        String slug = "cr15-other-" + data.tag().toLowerCase();
        short gameId = ensureGame(dsl, slug.toUpperCase(), "CR15 Other " + slug, slug);
        long single = createVariant(dsl, data, gameId, "SINGLE_CARD");
        long accessory = createVariant(dsl, data, gameId, "ACCESSORY");
        OffsetDateTime activeAt = OffsetDateTime.parse("2024-03-05T10:00:00+07:00");
        OffsetDateTime closedAt = OffsetDateTime.parse("2024-03-11T00:01:00+07:00");
        createHistoricalListingStock(dsl, seller.profileId(), single, 100, activeAt, closedAt);
        createHistoricalListingStock(dsl, seller.profileId(), accessory, 50, activeAt, closedAt);
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), single,
                "COMPLETED", OffsetDateTime.parse("2024-03-06T12:00:00+07:00"),
                2, new BigDecimal("100.00"), new BigDecimal("200.00"), new BigDecimal("6.00"));
        insertOrderWithItem(dsl, data, buyer.userId(), seller.profileId(), accessory,
                "COMPLETED", OffsetDateTime.parse("2024-03-06T13:00:00+07:00"),
                1, new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("3.00"));

        var report = reportService.report("2024-03-04", "2024-03-10", slug, null);

        assertThat(report.summary().gmv()).isEqualByComparingTo("300.00");
        assertThat(report.chartData().stream()
                .map(day -> day.singles().add(day.sealed()).add(day.other()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("300.00");
        assertThat(report.breakdownTable()).singleElement().satisfies(row -> {
            assertThat(row.singleCards()).isEqualTo(100);
            assertThat(row.sealedBoxes()).isZero();
            assertThat(row.otherItems()).isEqualTo(50);
            assertThat(row.remainingStock()).isEqualTo(150);
        });
        assertThat(report.totals().otherItems()).isEqualTo(50);

        var onlyOther = reportService.report("2024-03-04", "2024-03-10", slug, "other");
        assertThat(onlyOther.summary().gmv()).isEqualByComparingTo("100.00");
        assertThat(onlyOther.summary().remainingStock()).isEqualTo(50);
    }

    @Test
    void aWeekWithStockAndNoSalesIsNotEmpty() {
        Seller seller = data.seller();
        String slug = "cr15-stock-" + data.tag().toLowerCase();
        short gameId = ensureGame(dsl, slug.toUpperCase(), "CR15 Stock " + slug, slug);
        long single = createVariant(dsl, data, gameId, "SINGLE_CARD");
        createHistoricalListingStock(dsl, seller.profileId(), single, 30,
                OffsetDateTime.parse("2024-04-02T10:00:00+07:00"),
                OffsetDateTime.parse("2024-04-08T00:01:00+07:00"));

        var withStock = reportService.report("2024-04-01", "2024-04-07", slug, null);
        assertThat(withStock.hasData()).isTrue();
        assertThat(withStock.summary().remainingStock()).isEqualTo(30);
        assertThat(withStock.summary().gmv()).isZero();
        assertThat(withStock.breakdownTable()).singleElement()
                .satisfies(row -> assertThat(row.remainingStock()).isEqualTo(30));

        assertThat(reportService.report("2024-04-08", "2024-04-14", slug, null).hasData()).isFalse();
    }

    @Test
    void thresholdEndpointsAreAdminOnly() {
        ApiClient client = new ApiClient(port);
        String url = "/api/v1/admin/reports/weekly-stock-profit/thresholds";
        Map<String, Object> body = Map.of("lowStockThreshold", 500, "fastMovingPercent", 30);

        assertThat(client.get(url, null).status()).isEqualTo(401);
        assertThat(client.put(url, null, body).status()).isEqualTo(401);
        assertThat(client.get(url, data.bearer(data.buyer())).status()).isEqualTo(403);
        assertThat(client.put(url, data.bearer(data.buyer()), body).status()).isEqualTo(403);
        String sellerToken = data.bearer(data.seller().principal());
        assertThat(client.get(url, sellerToken).status()).isEqualTo(403);
        assertThat(client.put(url, sellerToken, body).status()).isEqualTo(403);
    }

    @Test
    void savedThresholdsDriveTheStockStatusAndInvalidOnesAreRefused() {
        seedStandardAugust2026Dataset(dsl, data);
        ApiClient client = new ApiClient(port);
        String admin = data.bearer(data.admin());
        String url = "/api/v1/admin/reports/weekly-stock-profit/thresholds";

        Response defaults = client.get(url, admin);
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.data().path("lowStockThreshold").asInt()).isEqualTo(500);
        assertThat(defaults.data().path("fastMovingPercent").asInt()).isEqualTo(30);
        assertThat(new BigDecimal(defaults.data().path("standardCommissionRatePercent").asText())).isPositive();
        assertThat(statuses(reportService.report("2026-08-03", "2026-08-09", null, null)))
                .containsExactly("NORMAL", "NORMAL", "NORMAL");

        for (Map<String, Object> invalid : java.util.List.<Map<String, Object>>of(
                Map.of("lowStockThreshold", -1, "fastMovingPercent", 30),
                Map.of("lowStockThreshold", 500, "fastMovingPercent", 0),
                Map.of("lowStockThreshold", 500, "fastMovingPercent", 101),
                Map.of("lowStockThreshold", 500, "fastMovingPercent", 30.5),
                Map.of("fastMovingPercent", 30))) {
            assertThat(client.put(url, admin, invalid).status()).isEqualTo(400);
        }
        assertThat(client.get(url, admin).data().path("fastMovingPercent").asInt()).isEqualTo(30);

        try {
            Response saved = client.put(url, admin, Map.of("lowStockThreshold", 1300, "fastMovingPercent", 20));
            assertThat(saved.status()).isEqualTo(200);
            assertThat(saved.data().path("lowStockThreshold").asInt()).isEqualTo(1300);
            assertThat(saved.data().path("fastMovingPercent").asInt()).isEqualTo(20);
            assertThat(statuses(reportService.report("2026-08-03", "2026-08-09", null, null)))
                    .containsExactly("NORMAL", "FAST_MOVING", "LOW_STOCK");

            assertThat(client.put("/api/v1/admin/settings/report.fast_moving_percent", admin,
                    Map.of("value", "abc")).status()).isEqualTo(400);
        } finally {
            client.put(url, admin, Map.of("lowStockThreshold", 500, "fastMovingPercent", 30));
        }
    }

    private static java.util.List<String> statuses(
            com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report report) {
        return report.breakdownTable().stream()
                .map(com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.BreakdownRow::status)
                .toList();
    }

    private static Map<String, String> unzipEntries(byte[] bytes) throws IOException {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
