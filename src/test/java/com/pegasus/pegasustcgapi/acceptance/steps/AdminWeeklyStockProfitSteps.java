package com.pegasus.pegasustcgapi.acceptance.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitIntegrationTest;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.BinaryResponse;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.TestData;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** Cucumber BDD step definitions for CR15_Admin_Weekly_Stock_and_Profit_Report.feature. */
public class AdminWeeklyStockProfitSteps {

    @LocalServerPort
    private int port;

    @Autowired
    private TestData data;

    @Autowired
    private DSLContext dsl;

    private Response response;
    private BinaryResponse rawResponse;

    @When("an unauthenticated caller requests the weekly stock and profit report")
    public void requestReport() {
        response = new ApiClient(port).get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-10-26&endDate=2026-11-01", null);
    }

    @When("an unauthenticated caller requests the weekly stock and profit export")
    public void requestExport() {
        response = new ApiClient(port).get(
                "/api/v1/admin/reports/weekly-stock-profit/export?startDate=2026-10-26&endDate=2026-11-01", null);
    }

    @Given("a buyer requests the weekly stock and profit report")
    public void buyerRequestsReport() {
        response = new ApiClient(port).get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-10-26&endDate=2026-11-01",
                data.bearer(data.buyer()));
    }

    @Given("a seller requests the weekly stock and profit report")
    public void sellerRequestsReport() {
        response = new ApiClient(port).get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-10-26&endDate=2026-11-01",
                data.bearer(data.seller().principal()));
    }

    @Given("the standard CR15 weekly dataset for August 2026 is seeded")
    public void seedStandardCr15Dataset() {
        AdminWeeklyStockProfitIntegrationTest.seedStandardAugust2026Dataset(dsl, data);
    }

    @When("an admin requests the weekly stock and profit report for {string} to {string}")
    public void adminRequestsReportForRange(String startDate, String endDate) {
        response = new ApiClient(port).get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=" + startDate + "&endDate=" + endDate,
                data.bearer(data.admin()));
    }

    @When("an admin exports the weekly stock and profit report for {string} to {string} as {string}")
    public void adminExportsReportForRange(String startDate, String endDate, String format) {
        rawResponse = new ApiClient(port).getBytes(
                "/api/v1/admin/reports/weekly-stock-profit/export?startDate=" + startDate
                        + "&endDate=" + endDate + "&format=" + format,
                data.bearer(data.admin()),
                "*/*");
    }

    @Then("the weekly report response is {int}")
    public void responseIs(int status) {
        assertThat(response.status()).isEqualTo(status);
    }

    @Then("the weekly report hasData is {word}")
    public void reportHasDataIs(String expected) {
        assertThat(response.data().path("hasData").asBoolean()).isEqualTo(Boolean.parseBoolean(expected));
    }

    @Then("the weekly report summary and totals match GMV {long}, profit {long}, commission {long}, initialStock {long}, soldCount {long}, remainingStock {long}, and wowChangePercent {double}")
    public void verifySummaryAndTotals(
            long gmv, long profit, long commission, long initialStock, long soldCount, long remainingStock, double wow) {
        JsonNode summary = response.data().path("summary");
        JsonNode totals = response.data().path("totals");

        assertThat(new BigDecimal(summary.path("weeklyGmv").asText())).isEqualByComparingTo(BigDecimal.valueOf(gmv));
        assertThat(new BigDecimal(summary.path("gmv").asText())).isEqualByComparingTo(BigDecimal.valueOf(gmv));
        assertThat(new BigDecimal(summary.path("netProfit").asText())).isEqualByComparingTo(BigDecimal.valueOf(profit));
        assertThat(new BigDecimal(summary.path("commissionCollected").asText())).isEqualByComparingTo(BigDecimal.valueOf(commission));
        assertThat(summary.path("initialStock").asLong()).isEqualTo(initialStock);
        assertThat(summary.path("soldCount").asLong()).isEqualTo(soldCount);
        assertThat(summary.path("remainingStock").asLong()).isEqualTo(remainingStock);
        assertThat(new BigDecimal(summary.path("wowChangePercent").asText()))
                .isEqualByComparingTo(BigDecimal.valueOf(wow));

        assertThat(new BigDecimal(totals.path("weeklyGmv").asText())).isEqualByComparingTo(BigDecimal.valueOf(gmv));
        assertThat(new BigDecimal(totals.path("netProfit").asText())).isEqualByComparingTo(BigDecimal.valueOf(profit));
        assertThat(new BigDecimal(totals.path("commissionCollected").asText())).isEqualByComparingTo(BigDecimal.valueOf(commission));
        assertThat(totals.path("initialStock").asLong()).isEqualTo(initialStock);
        assertThat(totals.path("soldCount").asLong()).isEqualTo(soldCount);
        assertThat(totals.path("remainingStock").asLong()).isEqualTo(remainingStock);
    }

    @Then("the weekly report breakdownTable rows sum to the totals and satisfy remainingStock equals initialStock minus soldCount")
    public void verifyBreakdownTableConsistency() {
        JsonNode breakdownTable = response.data().path("breakdownTable");
        JsonNode totals = response.data().path("totals");

        assertThat(breakdownTable.isArray()).isTrue();
        assertThat(breakdownTable.size()).isEqualTo(3);

        long sumInitial = 0;
        long sumSold = 0;
        long sumRemaining = 0;
        BigDecimal sumGmv = BigDecimal.ZERO;
        BigDecimal sumProfit = BigDecimal.ZERO;

        for (JsonNode row : breakdownTable) {
            long initial = row.path("initialStock").asLong();
            long sold = row.path("soldCount").asLong();
            long remaining = row.path("remainingStock").asLong();
            long singles = row.path("singleCards").asLong();
            long sealed = row.path("sealedBoxes").asLong();

            assertThat(remaining).isEqualTo(initial - sold);
            assertThat(singles + sealed).isEqualTo(remaining);

            sumInitial += initial;
            sumSold += sold;
            sumRemaining += remaining;
            sumGmv = sumGmv.add(new BigDecimal(row.path("gmv").asText()));
            sumProfit = sumProfit.add(new BigDecimal(row.path("netProfit").asText()));
        }

        assertThat(sumInitial).isEqualTo(totals.path("initialStock").asLong());
        assertThat(sumSold).isEqualTo(totals.path("soldCount").asLong());
        assertThat(sumRemaining).isEqualTo(totals.path("remainingStock").asLong());
        assertThat(sumGmv).isEqualByComparingTo(new BigDecimal(totals.path("weeklyGmv").asText()));
        assertThat(sumProfit).isEqualByComparingTo(new BigDecimal(totals.path("netProfit").asText()));
    }

    @Then("the weekly report gameDistribution has shares {int}, {int}, and {int} percent")
    public void verifyGameDistributionShares(int first, int second, int third) {
        JsonNode dist = response.data().path("gameDistribution");
        assertThat(dist.size()).isEqualTo(3);
        assertThat(new BigDecimal(dist.get(0).path("percentage").asText())).isEqualByComparingTo(BigDecimal.valueOf(first));
        assertThat(new BigDecimal(dist.get(1).path("percentage").asText())).isEqualByComparingTo(BigDecimal.valueOf(second));
        assertThat(new BigDecimal(dist.get(2).path("percentage").asText())).isEqualByComparingTo(BigDecimal.valueOf(third));
    }

    @Then("the weekly report summary has zero figures and wowChangePercent {double}")
    public void verifyEmptyReportSummary(double expectedWow) {
        JsonNode summary = response.data().path("summary");
        assertThat(new BigDecimal(summary.path("weeklyGmv").asText())).isZero();
        assertThat(new BigDecimal(summary.path("netProfit").asText())).isZero();
        assertThat(new BigDecimal(summary.path("commissionCollected").asText())).isZero();
        assertThat(summary.path("initialStock").asLong()).isZero();
        assertThat(summary.path("soldCount").asLong()).isZero();
        assertThat(summary.path("remainingStock").asLong()).isZero();
        assertThat(new BigDecimal(summary.path("wowChangePercent").asText()))
                .isEqualByComparingTo(BigDecimal.valueOf(expectedWow));
    }

    @Then("the exported weekly report status is {int} with content type {string} and filename {string}")
    public void verifyExportHeaders(int status, String contentType, String filename) {
        assertThat(rawResponse.status()).isEqualTo(status);
        assertThat(rawResponse.header("content-type").orElse("")).contains(contentType);
        assertThat(rawResponse.header("content-disposition").orElse("")).contains(filename);
    }

    @Then("the exported CSV content matches the main report figures")
    public void verifyExportedCsvContent() {
        String csv = new String(rawResponse.body(), StandardCharsets.UTF_8);
        assertThat(csv.trim().split("\\r?\\n")).hasSize(12);
        assertThat(csv).contains("\"summary\",\"\",\"\",\"\",\"\",\"428500.00\",\"12855.00\",\"12855.00\",\"6184\",\"1470\",\"18.2\"");
    }
}
