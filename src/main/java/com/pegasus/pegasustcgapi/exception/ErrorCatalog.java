package com.pegasus.pegasustcgapi.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes every {@link ErrorCode} with its default message as {@code { "<CODE>": "<message>" }}, the
 * English source for the Tolgee namespace {@code errors} that the web app translates codes with.
 * Run by {@code ./gradlew exportErrorCatalog}; CI pushes the file to Tolgee so a new code needs no manual key.
 */
public final class ErrorCatalog {

    private ErrorCatalog() {
    }

    /** Codes in declaration order, so the file diffs cleanly when one is added. */
    public static Map<String, String> messages() {
        Map<String, String> messages = new LinkedHashMap<>();
        Arrays.stream(ErrorCode.values()).forEach(code -> messages.put(code.name(), code.defaultMessage()));
        return messages;
    }

    static String toJson(Map<String, String> messages) {
        StringBuilder json = new StringBuilder("{\n");
        var entries = messages.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            json.append("  ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
            json.append(entries.hasNext() ? ",\n" : "\n");
        }
        return json.append("}\n").toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/i18n/errors/en.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, toJson(messages()), StandardCharsets.UTF_8);
        System.out.println("Wrote " + messages().size() + " error codes to " + out);
    }
}
