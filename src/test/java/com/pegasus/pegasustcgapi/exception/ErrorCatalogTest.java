package com.pegasus.pegasustcgapi.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorCatalogTest {

    @Test
    @DisplayName("the catalog lists every ErrorCode with its default message")
    void listsEveryCode() {
        Map<String, String> messages = ErrorCatalog.messages();

        assertThat(messages).hasSize(ErrorCode.values().length);
        Arrays.stream(ErrorCode.values())
                .forEach(code -> assertThat(messages).containsEntry(code.name(), code.defaultMessage()));
    }

    @Test
    @DisplayName("the exported file is valid JSON that Tolgee can import")
    void writesValidJson(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("errors/en.json");

        ErrorCatalog.main(new String[] {out.toString()});

        Map<String, String> parsed = new ObjectMapper().readValue(
                Files.readString(out), new TypeReference<Map<String, String>>() {});
        assertThat(parsed).isEqualTo(ErrorCatalog.messages());
    }

    @Test
    @DisplayName("quotes, backslashes and control characters are escaped")
    void escapes() throws Exception {
        String json = ErrorCatalog.toJson(Map.of("X", "say \"hi\" \\ now\n"));

        assertThat(new ObjectMapper().readValue(json, new TypeReference<Map<String, String>>() {}))
                .containsEntry("X", "say \"hi\" \\ now\n");
    }
}
