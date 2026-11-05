package com.pegasus.pegasustcgapi.acceptance.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.ApiClient.Response;
import com.pegasus.pegasustcgapi.support.TestData;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** HTTP security checks for CR15; report arithmetic is covered by service and repository tests. */
public class AdminWeeklyStockProfitSteps {

    @LocalServerPort
    private int port;

    @Autowired
    private TestData data;

    private Response response;

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

    @io.cucumber.java.en.Given("a buyer requests the weekly stock and profit report")
    public void buyerRequestsReport() {
        response = new ApiClient(port).get(
                "/api/v1/admin/reports/weekly-stock-profit?startDate=2026-10-26&endDate=2026-11-01",
                data.bearer(data.buyer()));
    }

    @Then("the weekly report response is {int}")
    public void responseIs(int status) {
        assertThat(response.status()).isEqualTo(status);
    }
}
