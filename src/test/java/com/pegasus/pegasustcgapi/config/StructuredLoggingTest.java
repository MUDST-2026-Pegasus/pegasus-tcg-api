package com.pegasus.pegasustcgapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.ObjectMapper;

class StructuredLoggingTest {

    @Test
    void logstashOutputIsJsonWithRootCorrelationAndServiceFields() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("logging-test", Map.of(
                "logging.structured.json.add.service", "pegasus-api",
                "logging.structured.json.add.env", "dev")));

        var context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.start();

        var event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.pegasus.Observation");
        event.setLevel(Level.INFO);
        event.setMessage("structured logging validation");
        event.setThreadName("test");
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(Map.of("traceId", "trace-123", "spanId", "span-456"));

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8).trim();
        var parsed = new ObjectMapper().readTree(json);

        assertThat(parsed.get("traceId").asString()).isEqualTo("trace-123");
        assertThat(parsed.get("spanId").asString()).isEqualTo("span-456");
        assertThat(parsed.get("service").asString()).isEqualTo("pegasus-api");
        assertThat(parsed.get("env").asString()).isEqualTo("dev");
        encoder.stop();
        context.stop();
    }
}
