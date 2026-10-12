package com.pegasus.pegasustcgapi.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real HTTP calls to the application a {@link PostgresIntegrationTest} started, through
 * the whole filter chain — JWT decoding, the security rules, the exception handler.
 *
 * <p>Built on the JDK's own client so the test classpath needs nothing extra. Thread
 * safe: the race test shares one instance between its threads.
 */
public class ApiClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /** What came back: the status, the parsed envelope and the headers. */
    public record Response(int status, JsonNode body, Map<String, String> headers) {

        public JsonNode data() {
            return body.path("data");
        }

        /** The machine-readable code of a failure, e.g. {@code ORDER_STATUS_TRANSITION}. */
        public String errorCode() {
            return data().path("code").asString("");
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase()));
        }
    }

    public Response get(String path, String bearer) {
        return send(request(path, bearer, Map.of()).GET());
    }

    public Response post(String path, String bearer, Object body) {
        return post(path, bearer, body, Map.of());
    }

    public Response post(String path, String bearer, Object body, Map<String, String> headers) {
        HttpRequest.BodyPublisher payload = body == null
                ? BodyPublishers.noBody()
                : BodyPublishers.ofString(JSON.writeValueAsString(body));
        return send(request(path, bearer, headers)
                .header("Content-Type", "application/json")
                .POST(payload));
    }

    private HttpRequest.Builder request(String path, String bearer, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        headers.forEach(builder::header);
        return builder;
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.build(), BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank()
                    ? JSON.createObjectNode()
                    : JSON.readTree(response.body());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            return new Response(response.statusCode(), body, headers);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the API", e);
        }
    }
}
