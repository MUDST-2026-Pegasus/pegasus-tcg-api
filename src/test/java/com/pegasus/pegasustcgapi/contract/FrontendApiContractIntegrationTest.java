package com.pegasus.pegasustcgapi.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * TCG-755: the calls the web app actually makes, checked against what this API publishes.
 *
 * <p>The consumer side is a snapshot of {@code pegasus-tcg-web} taken by
 * {@code scripts/contract/extract_fe_endpoints.py} into {@code src/test/resources/contract}.
 * Re-run the script after a web change; a failure here names the producer (this API's
 * OpenAPI document) and the consumer file, which is what a contract defect needs.
 */
@Tag("cross-module")
@DisplayName("TCG-755 FE ↔ BE API contract")
class FrontendApiContractIntegrationTest extends PostgresIntegrationTest {

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(port);
    }

    @Test
    @DisplayName("every endpoint the web app calls is published, with the same HTTP method")
    void everyConsumedEndpointIsPublished() {
        OpenApiContract spec = OpenApiContract.fetch(api);
        List<ContractFixtures.ConsumedEndpoint> consumed = ContractFixtures.feEndpoints();
        assertThat(consumed).as("FE snapshot is empty — re-run the extractor").hasSizeGreaterThan(100);
        java.util.Map<String, String> knownGaps = ContractFixtures.knownGaps();

        List<String> missing = new ArrayList<>();
        Set<String> stillMissingKnown = new TreeSet<>();
        for (ContractFixtures.ConsumedEndpoint call : consumed) {
            String key = call.method() + " " + call.path();
            if (spec.publishes(call.method(), call.path())) {
                continue;
            }
            if (knownGaps.containsKey(key)) {
                stillMissingKnown.add(key);
                continue;
            }
            missing.add(call.method() + " /api/v1" + call.path() + "  <- " + call.consumer()
                    + "  (published methods for this path: " + spec.methodsFor(call.path()) + ")");
        }
        assertThat(missing)
                .as("FE calls with no matching operation in /v1/api-docs (producer: pegasus-tcg-api, "
                        + "consumer file listed) — each line is a new contract defect")
                .isEmpty();
        assertThat(stillMissingKnown)
                .as("fe-endpoints-known-gaps.tsv lists a gap that is fixed or no longer called — remove the line")
                .containsExactlyInAnyOrderElementsOf(knownGaps.keySet());
    }

    /** DEF-755-02 / DEF-755-03: the dormant FE calls in fe-endpoints-known-gaps.tsv. Fails until they are resolved. */
    @Test
    @Tag("known-defect")
    @DisplayName("DEF-755-02/03 no FE call is waiting on a missing endpoint")
    void noKnownGapsRemain() {
        assertThat(ContractFixtures.knownGaps()).as("open FE→BE gaps").isEmpty();
    }

    /**
     * DEF-755-01: OrderController and MockPaymentController both declare {@code @Tag(name = "Orders")}
     * with different descriptions, so /v1/api-docs lists the tag twice. Tag names must be unique
     * in OpenAPI; strict tools (swagger-parser, client generators) refuse the document. Fails until fixed.
     */
    @Test
    @Tag("known-defect")
    @DisplayName("DEF-755-01 /v1/api-docs is a valid OpenAPI document (no repeated tags)")
    void publishedDocumentIsValidOpenApi() {
        OpenApiContract spec = OpenApiContract.fetch(api);
        assertThat(spec.duplicateTags()).as("top-level tags declared more than once").isEmpty();
    }

    /**
     * DEF-755-05: nullable response fields are published without "null" in their type, e.g.
     * UserResponse.bio/phone/avatarUrl. The web app's hand-written types already allow null, but any
     * client generated from /v1/api-docs would reject real responses. Journeys log every such field
     * to build/tcg755/undocumented-nulls.txt. Fails until springdoc emits nullable types.
     */
    @Test
    @Tag("known-defect")
    @DisplayName("DEF-755-05 fields the API sends as null are documented as nullable")
    void nullableFieldsAreDocumented() {
        OpenApiContract spec = OpenApiContract.fetch(api);
        ApiClient.Response me = api.get("/api/v1/auth/me", data.bearer(data.buyer()));
        assertThat(me.status()).isEqualTo(200);
        assertThat(spec.violations("GET", "/api/v1/auth/me", me))
                .filteredOn(OpenApiContract::isUndocumentedNull)
                .as("null values the schema does not allow")
                .isEmpty();
    }

    @Test
    @DisplayName("every server error code the web app translates still exists in ErrorCode")
    void everyErrorCodeTheWebAppMapsExists() {
        Set<String> produced = Arrays.stream(ErrorCode.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> unknown = ContractFixtures.feErrorCodes().stream()
                .filter(code -> !produced.contains(code))
                .toList();
        assertThat(unknown)
                .as("API_ERROR_CODES in pegasus-tcg-web/src/lib/api/types.ts that the API never sends")
                .isEmpty();
    }

    @Test
    @DisplayName("the success envelope and PageResponse carry the fields lib/api/types.ts reads")
    void successEnvelopeAndPageShape() {
        ApiClient.Response page = api.get("/api/v1/listings?page=0&size=5", null);

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("success").isBoolean()).isTrue();
        assertThat(page.body().path("success").asBoolean()).isTrue();
        assertThat(page.body().has("message")).isTrue();
        JsonNode data = page.data();
        for (String field : List.of("items", "page", "size", "totalItems", "totalPages")) {
            assertThat(data.has(field)).as("PageResponse.%s", field).isTrue();
        }
        assertThat(data.path("items").isArray()).isTrue();
        assertThat(data.path("page").isIntegralNumber()).isTrue();
        assertThat(data.path("size").asInt()).isEqualTo(5);
        assertThat(data.path("totalItems").isIntegralNumber()).isTrue();
        assertThat(OpenApiContract.fetch(api).violations("GET", "/api/v1/listings", page)).isEmpty();
    }

    @Test
    @DisplayName("the schema validator is not vacuous: a body that breaks the documented types is rejected")
    void validatorRejectsABrokenBody() {
        OpenApiContract spec = OpenApiContract.fetch(api);
        ApiClient.Response real = api.get("/api/v1/listings?page=0&size=1", null);
        tools.jackson.databind.node.ObjectNode broken = (tools.jackson.databind.node.ObjectNode) real.body().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) broken.get("data")).put("totalItems", "many");
        ((tools.jackson.databind.node.ObjectNode) broken.get("data")).put("items", "not-an-array");
        ApiClient.Response tampered = new ApiClient.Response(200, broken, real.headers());

        assertThat(spec.violations("GET", "/api/v1/listings", real)).isEmpty();
        assertThat(spec.violations("GET", "/api/v1/listings", tampered)).isNotEmpty();
        // Coverage of the check itself: how many FE-consumed operations document a JSON body schema.
        long documented = ContractFixtures.feEndpoints().stream()
                .filter(call -> spec.hasJsonSuccessSchema(call.method(), call.path())).count();
        System.out.printf("TCG-755 OpenAPI %s; schema coverage: %d of %d FE-consumed calls document a JSON 2xx schema; "
                + "2xx media types: %s%n", spec.version(), documented, ContractFixtures.feEndpoints().size(),
                spec.successMediaTypes());
        assertThat(documented).isGreaterThan(ContractFixtures.feEndpoints().size() / 2);
    }

    @Test
    @DisplayName("errors come back as success=false with data.code, path and timestamp")
    void errorEnvelopeShape() {
        ApiClient.Response unauthenticated = api.get("/api/v1/orders", null);
        assertErrorEnvelope(unauthenticated, 401, "UNAUTHENTICATED");

        ApiClient.Response badToken = api.get("/api/v1/orders", "Bearer not-a-jwt");
        assertThat(badToken.status()).isEqualTo(401);
        assertThat(badToken.body().path("success").asBoolean(true)).isFalse();
        assertThat(badToken.errorCode()).isIn("INVALID_TOKEN", "UNAUTHENTICATED");

        String buyer = data.bearer(data.buyer());
        ApiClient.Response forbidden = api.get("/api/v1/admin/orders", buyer);
        assertErrorEnvelope(forbidden, 403, "ACCESS_DENIED");

        ApiClient.Response invalid = api.post("/api/v1/cart/items", buyer, java.util.Map.of("quantity", 0));
        assertErrorEnvelope(invalid, 400, "VALIDATION_FAILED");
        JsonNode violations = invalid.data().path("violations");
        assertThat(violations.isArray()).isTrue();
        assertThat(violations).isNotEmpty();
        assertThat(violations.get(0).has("field")).isTrue();
        assertThat(violations.get(0).has("message")).isTrue();

        ApiClient.Response notFound = api.get("/api/v1/orders/987654321", buyer);
        assertErrorEnvelope(notFound, 404, null);
    }

    private static void assertErrorEnvelope(ApiClient.Response response, int status, String code) {
        assertThat(response.status()).as(response.body().toString()).isEqualTo(status);
        assertThat(response.body().path("success").asBoolean(true)).isFalse();
        assertThat(response.body().path("message").isString()).isTrue();
        assertThat(response.errorCode()).isNotBlank();
        if (code != null) {
            assertThat(response.errorCode()).isEqualTo(code);
        }
        assertThat(ErrorCode.valueOf(response.errorCode())).isNotNull();
        assertThat(response.data().path("timestamp").isString()).isTrue();
    }
}
