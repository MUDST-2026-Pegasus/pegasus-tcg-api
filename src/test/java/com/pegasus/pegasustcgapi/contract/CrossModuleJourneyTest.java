package com.pegasus.pegasustcgapi.contract;

import static com.pegasus.pegasustcgapi.jooq.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.support.ApiClient;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;

/**
 * TCG-755 base for cross-module journeys driven the way the web app drives them: real HTTP
 * through the security chain, the paths in fe-endpoints.tsv, a real Postgres (inherited) and
 * a real MinIO, with nothing mocked between the controller and the database.
 *
 * <p>Every journey checks three things at each step: the response matches the published
 * OpenAPI schema, the fields the web app's TypeScript types read are present with the right
 * JSON type ({@link FeShape}), and the rows/objects the step should leave behind are there.
 * Each test seeds its own synthetic people under a unique tag, so classes can run in any order.
 */
@Tag("cross-module")
public abstract class CrossModuleJourneyTest extends PostgresIntegrationTest {

    /** Same pinned image as docker-compose.yaml, so tests and local dev run one MinIO build. */
    static final GenericContainer<?> MINIO = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .withEnv("MINIO_ROOT_USER", "tcg755-test")
            .withEnv("MINIO_ROOT_PASSWORD", "tcg755-test-secret")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        registry.add("pegasus.storage.endpoint", () -> endpoint);
        registry.add("pegasus.storage.public-endpoint", () -> endpoint);
        registry.add("pegasus.storage.access-key", () -> "tcg755-test");
        registry.add("pegasus.storage.secret-key", () -> "tcg755-test-secret");
        registry.add("pegasus.storage.bucket", () -> "tcg755");
        registry.add("pegasus.storage.public-read", () -> "false");
    }

    protected static final String PASSWORD = "Tcg755-Journey!";

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected ApiClient api;
    private OpenApiContract spec;
    private final List<String> contractViolations = new ArrayList<>();
    private final HttpClient raw = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void journeyClient() {
        api = new ApiClient(port);
        contractViolations.clear();
    }

    /** Schema violations are collected through the journey and reported together at the end. */
    @AfterEach
    void noContractViolations() {
        assertThat(contractViolations)
                .as("responses that do not match /v1/api-docs (producer: pegasus-tcg-api)")
                .isEmpty();
    }

    protected OpenApiContract spec() {
        if (spec == null) {
            spec = OpenApiContract.fetch(api);
        }
        return spec;
    }

    /** Checks the status, then records any schema mismatch of the body. */
    protected ApiClient.Response expect(int status, String method, String path, ApiClient.Response response) {
        assertThat(response.status()).as(method + " " + path + " → " + response.body()).isEqualTo(status);
        for (String violation : spec().violations(method, path, response)) {
            if (OpenApiContract.isUndocumentedNull(violation)) {
                UndocumentedNulls.record(violation);
            } else {
                contractViolations.add(violation);
            }
        }
        return response;
    }

    // ---------- auth ----------

    /** Gives a seeded account a real password, so the journey can sign in through /auth/login. */
    protected String login(AuthPrincipal who) {
        dsl.update(USER_ACCOUNT)
                .set(USER_ACCOUNT.PASSWORD_HASH, passwordEncoder.encode(PASSWORD))
                .set(USER_ACCOUNT.EMAIL_VERIFIED_AT, OffsetDateTime.now())
                .where(USER_ACCOUNT.ID.eq(who.userId()))
                .execute();
        ApiClient.Response signedIn = expect(200, "POST", "/api/v1/auth/login",
                api.post("/api/v1/auth/login", null, Map.of("email", who.email(), "password", PASSWORD)));
        FeShape.of("AuthTokens (auth.types.ts)").field("accessToken", "string").field("tokenType", "string")
                .field("expiresIn", "number").field("refreshExpiresIn", "number")
                .check(signedIn.data().path("tokens"));
        assertThat(signedIn.data().path("tokens").has("refreshToken"))
                .as("refresh token must only travel in the HttpOnly cookie").isFalse();
        assertThat(signedIn.header("set-cookie")).hasValueSatisfying(cookie ->
                assertThat(cookie).startsWith("pegasus_rt=").containsIgnoringCase("HttpOnly"));
        return "Bearer " + signedIn.data().path("tokens").path("accessToken").asString();
    }

    // ---------- storage ----------

    /** presign → PUT to MinIO → the key a feature endpoint is then given. */
    protected String upload(String purpose, String bearer, byte[] bytes, String contentType) {
        ApiClient.Response presigned = expect(200, "POST", "/api/v1/uploads/presign",
                api.post("/api/v1/uploads/presign", bearer,
                        Map.of("purpose", purpose, "contentType", contentType, "sizeBytes", bytes.length)));
        FeShape.of("PresignUploadResponse (upload.types.ts)").field("objectKey", "string")
                .field("uploadUrl", "string").field("expiresInSeconds", "number").field("maxBytes", "number")
                .check(presigned.data());
        HttpResponse<String> put = send(HttpRequest.newBuilder(URI.create(presigned.data().path("uploadUrl").asString()))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build());
        assertThat(put.statusCode()).as("PUT to presigned URL: " + put.body()).isEqualTo(200);
        return presigned.data().path("objectKey").asString();
    }

    protected byte[] download(String url) {
        HttpResponse<byte[]> response = sendBytes(HttpRequest.newBuilder(URI.create(url)).GET().build());
        assertThat(response.statusCode()).as("GET " + url).isEqualTo(200);
        return response.body();
    }

    protected int statusOf(String url) {
        return sendBytes(HttpRequest.newBuilder(URI.create(url)).GET().build()).statusCode();
    }

    protected static byte[] png(int seed) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                image.setRGB(x, y, (seed * 31 + x * 7 + y * 13) & 0xFFFFFF);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return raw.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return raw.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static JsonNode only(JsonNode array) {
        assertThat(array.isArray()).isTrue();
        assertThat(array).hasSize(1);
        return array.get(0);
    }
}
