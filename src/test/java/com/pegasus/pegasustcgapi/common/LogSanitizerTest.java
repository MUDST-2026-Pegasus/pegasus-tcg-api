package com.pegasus.pegasustcgapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogSanitizerTest {

    @Test
    @DisplayName("null input returns null")
    void nullInputReturnsNull() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
        assertThat(LogSanitizer.sanitize(null, 100)).isNull();
    }

    @Test
    @DisplayName("empty string returns empty string")
    void emptyStringReturnsEmpty() {
        assertThat(LogSanitizer.sanitize("")).isEmpty();
    }

    @Test
    @DisplayName("clean printable text and Thai characters are preserved untouched")
    void cleanTextPreserved() {
        String input = "Pikachu EX 151 - โปเกมอนการ์ด ⚡";
        assertThat(LogSanitizer.sanitize(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("encodes carriage return (\\r) to literal \\r")
    void encodesCarriageReturn() {
        String input = "line1\rline2";
        String sanitized = LogSanitizer.sanitize(input);

        assertThat(sanitized).isEqualTo("line1\\rline2");
        assertThat(sanitized).doesNotContain("\r");
    }

    @Test
    @DisplayName("encodes newline (\\n) to literal \\n")
    void encodesNewline() {
        String input = "line1\nline2";
        String sanitized = LogSanitizer.sanitize(input);

        assertThat(sanitized).isEqualTo("line1\\nline2");
        assertThat(sanitized).doesNotContain("\n");
    }

    @Test
    @DisplayName("encodes CRLF (\\r\\n) log injection payload on a single line")
    void encodesCrLfLogInjectionPayload() {
        String payload = "product-1\r\n2026-11-02 10:00:00 [ERROR] Admin credentials breached\r\n";
        String sanitized = LogSanitizer.sanitize(payload);

        assertThat(sanitized).isEqualTo(
                "product-1\\r\\n2026-11-02 10:00:00 [ERROR] Admin credentials breached\\r\\n");
        assertThat(sanitized).doesNotContain("\r");
        assertThat(sanitized).doesNotContain("\n");
    }

    @Test
    @DisplayName("encodes Unicode line separator (\\u2028)")
    void encodesUnicodeLineSeparator() {
        String input = "heading\u2028subtext";
        String sanitized = LogSanitizer.sanitize(input);

        assertThat(sanitized).isEqualTo("heading\\u2028subtext");
        assertThat(sanitized).doesNotContain("\u2028");
    }

    @Test
    @DisplayName("encodes Unicode paragraph separator (\\u2029)")
    void encodesUnicodeParagraphSeparator() {
        String input = "paragraph1\u2029paragraph2";
        String sanitized = LogSanitizer.sanitize(input);

        assertThat(sanitized).isEqualTo("paragraph1\\u2029paragraph2");
        assertThat(sanitized).doesNotContain("\u2029");
    }

    @Test
    @DisplayName("encodes Unicode next line NEL (\\u0085)")
    void encodesUnicodeNextLine() {
        String input = "item1\u0085item2";
        String sanitized = LogSanitizer.sanitize(input);

        assertThat(sanitized).isEqualTo("item1\\u0085item2");
        assertThat(sanitized).doesNotContain("\u0085");
    }

    @Test
    @DisplayName("encodes tab, backspace, form feed and ASCII control codes")
    void encodesStandardAsciiControls() {
        assertThat(LogSanitizer.sanitize("col1\tcol2")).isEqualTo("col1\\tcol2");
        assertThat(LogSanitizer.sanitize("back\bspace")).isEqualTo("back\\bspace");
        assertThat(LogSanitizer.sanitize("page\ffeed")).isEqualTo("page\\ffeed");
        assertThat(LogSanitizer.sanitize("null\0byte")).isEqualTo("null\\u0000byte");
        assertThat(LogSanitizer.sanitize("escape\u001bcode")).isEqualTo("escape\\u001bcode");
        assertThat(LogSanitizer.sanitize("delete\u007fchar")).isEqualTo("delete\\u007fchar");
        assertThat(LogSanitizer.sanitize("vert\u000btab")).isEqualTo("vert\\u000btab");
        assertThat(LogSanitizer.sanitize("record\u001esep")).isEqualTo("record\\u001esep");
        assertThat(LogSanitizer.sanitize("unit\u001fsep")).isEqualTo("unit\\u001fsep");
    }

    @Test
    @DisplayName("encodes Unicode format and bidirectional control characters")
    void encodesUnicodeFormatAndBidiControls() {
        // \u202E is RIGHT-TO-LEFT OVERRIDE used in bidi text spoofing
        String bidiSpoof = "admin\u202Egnirts";
        String sanitizedBidi = LogSanitizer.sanitize(bidiSpoof);
        assertThat(sanitizedBidi).isEqualTo("admin\\u202egnirts");
        assertThat(sanitizedBidi).doesNotContain("\u202E");

        // \u200B is ZERO WIDTH SPACE
        String zws = "zero\u200Bwidth";
        String sanitizedZws = LogSanitizer.sanitize(zws);
        assertThat(sanitizedZws).isEqualTo("zero\\u200bwidth");
        assertThat(sanitizedZws).doesNotContain("\u200B");
    }

    @Test
    @DisplayName("truncates excessively long input to limit unnecessary data")
    void truncatesLongInput() {
        String longInput = "a".repeat(100);
        String sanitized = LogSanitizer.sanitize(longInput, 10);

        assertThat(sanitized).isEqualTo("aaaaaaaaaa...[truncated]");
    }

    @Test
    @DisplayName("truncates at DEFAULT_MAX_LENGTH when using default sanitize")
    void truncatesAtDefaultMaxLength() {
        String longInput = "x".repeat(LogSanitizer.DEFAULT_MAX_LENGTH + 50);
        String sanitized = LogSanitizer.sanitize(longInput);

        assertThat(sanitized)
                .startsWith("x".repeat(LogSanitizer.DEFAULT_MAX_LENGTH))
                .endsWith("...[truncated]");
    }

    @Test
    @DisplayName("does not split surrogate pairs when truncating at boundary")
    void doesNotSplitSurrogatePairs() {
        // Each emoji is 2 char code units (high surrogate + low surrogate)
        // 4 emojis = 8 chars. If we cut at 5, char 4 is high surrogate.
        String emojis = "😀😀😀😀";
        String sanitized = LogSanitizer.sanitize(emojis, 5);

        // Should cut to 4 chars (2 emojis) rather than leaving an orphaned high surrogate
        assertThat(sanitized).isEqualTo("😀😀...[truncated]");
    }

    @ParameterizedTest(name = "verifies separator {0} is never present in sanitized output")
    @ValueSource(strings = {"\r", "\n", "\r\n", "\u2028", "\u2029", "\u0085", "\u000B", "\u001E", "\u001F"})
    void verifiesSeparatorsNeverPresentInOutput(String separator) {
        String input = "start" + separator + "end";
        String sanitized = LogSanitizer.sanitize(input);

        assertThat(sanitized).doesNotContain(separator);
        assertThat(sanitized).startsWith("start\\");
        assertThat(sanitized).endsWith("end");
    }

    @Test
    @DisplayName("sanitizes complex attack payload combining CRLF, Unicode separators, ANSI escape, and tab")
    void sanitizesComplexAttackPayload() {
        String complexAttack = "GET /api/v1/cards HTTP/1.1\r\n"
                + "Host: evil.com\t\u001b[31m[FAKE LOG]\u2028"
                + "2026-11-02 12:00:00.000 [INFO] User admin logged in\u2029\u0085";

        String sanitized = LogSanitizer.sanitize(complexAttack);

        assertThat(sanitized).doesNotContain("\r");
        assertThat(sanitized).doesNotContain("\n");
        assertThat(sanitized).doesNotContain("\u2028");
        assertThat(sanitized).doesNotContain("\u2029");
        assertThat(sanitized).doesNotContain("\u0085");
        assertThat(sanitized).doesNotContain("\t");
        assertThat(sanitized).doesNotContain("\u001b");

        assertThat(sanitized).contains("GET /api/v1/cards HTTP/1.1\\r\\nHost: evil.com\\t\\u001b[31m[FAKE LOG]\\u2028");
    }
}
