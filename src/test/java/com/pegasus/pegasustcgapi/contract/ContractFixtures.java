package com.pegasus.pegasustcgapi.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The consumer snapshots under {@code src/test/resources/contract} (see extract_fe_endpoints.py). */
final class ContractFixtures {

    /** One call site in the web app; {@code path} is under /api/v1 with parameters as {p}. */
    record ConsumedEndpoint(String method, String path, String consumer) {
    }

    private ContractFixtures() {
    }

    static List<ConsumedEndpoint> feEndpoints() {
        return lines("/contract/fe-endpoints.tsv").stream()
                .map(line -> line.split("\t"))
                .map(cols -> new ConsumedEndpoint(cols[0], cols[1], cols[2]))
                .toList();
    }

    /** Known FE→BE gaps, each with its defect id: "METHOD path" → "DEF-… note". */
    static java.util.Map<String, String> knownGaps() {
        java.util.Map<String, String> gaps = new java.util.TreeMap<>();
        lines("/contract/fe-endpoints-known-gaps.tsv").forEach(line -> {
            String[] cols = line.split("\t", 4);
            gaps.put(cols[0] + " " + cols[1], cols[2] + " " + (cols.length > 3 ? cols[3] : ""));
        });
        return gaps;
    }

    static List<String> feErrorCodes() {
        return lines("/contract/fe-error-codes.txt");
    }

    private static List<String> lines(String resource) {
        try (InputStream in = ContractFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
