package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

class JdbcTracingCorsIntegrationTest extends PostgresIntegrationTest {

    private static final String WEB_ORIGIN = "http://localhost:3000";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void browserTraceContextPreflightIsAllowed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + ApiPaths.CART))
                .header("Origin", WEB_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "traceparent, tracestate, baggage")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains(WEB_ORIGIN);
        String allowedHeaders = response.headers().firstValue("Access-Control-Allow-Headers")
                .orElse("").toLowerCase(Locale.ROOT);
        assertThat(allowedHeaders).contains("traceparent", "tracestate", "baggage");
    }

    @Test
    void dataSourceIsInstrumentedAndSqlBindValuesAreDisabled() {
        assertThat(AopUtils.isAopProxy(dataSource)).isTrue();
        assertThat(environment.getProperty(
                "jdbc.datasource-proxy.include-parameter-values", Boolean.class))
                .isEqualTo(Boolean.FALSE);
    }
}
