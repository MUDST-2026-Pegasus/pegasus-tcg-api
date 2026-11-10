package com.pegasus.pegasustcgapi.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvExportTest {

    @Test
    @DisplayName("writes a BOM, CRLF line ends and quotes every cell")
    void format() {
        CsvExport file = CsvExport.builder("name", "total")
                .row("ร้าน \"ดี\"", new BigDecimal("1500.50"))
                .row(null, 3)
                .build("x.csv");

        String text = new String(file.bytes(), StandardCharsets.UTF_8);

        assertThat(text).isEqualTo("﻿\"name\",\"total\"\r\n"
                + "\"ร้าน \"\"ดี\"\"\",\"1500.50\"\r\n"
                + "\"\",\"3\"\r\n");
        assertThat(file.filename()).isEqualTo("x.csv");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+cmd", "-2+3", "@SUM(A1)", "\tx", "\rx"})
    @DisplayName("prefixes text that a spreadsheet would run as a formula")
    void guardsFormulas(String text) {
        assertThat(CsvExport.cell(text)).isEqualTo("'" + text);
    }

    @Test
    @DisplayName("leaves numbers alone, so a negative amount stays a number")
    void numbersAreNotGuarded() {
        assertThat(CsvExport.cell(new BigDecimal("-25.00"))).isEqualTo("-25.00");
        assertThat(CsvExport.cell(-4)).isEqualTo("-4");
        assertThat(CsvExport.cell("plain-name")).isEqualTo("plain-name");
        assertThat(CsvExport.cell("")).isEmpty();
    }

    @Test
    @DisplayName("a row with the wrong number of cells is a bug, not a short line")
    void rowWidthIsChecked() {
        assertThatThrownBy(() -> CsvExport.builder("a", "b").row("only one"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses more than MAX_ROWS rows with EXPORT_TOO_LARGE")
    void rowLimit() {
        CsvExport.checkRowCount(CsvExport.MAX_ROWS);

        assertThatThrownBy(() -> CsvExport.checkRowCount(CsvExport.MAX_ROWS + 1L))
                .isInstanceOf(BadRequestException.class)
                .satisfies(e -> assertThat(((BadRequestException) e).errorCode())
                        .isEqualTo(ErrorCode.EXPORT_TOO_LARGE));
    }
}
