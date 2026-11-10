package com.pegasus.pegasustcgapi.common.csv;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A finished CSV file for an admin download.
 *
 * <p>The format is the one Excel opens Thai text correctly from: UTF-8 with a BOM,
 * CRLF line ends, every cell in double quotes. Text cells that a spreadsheet would
 * run as a formula get a leading {@code '}; numbers are written as they are, so a
 * negative amount stays a number.
 */
public record CsvExport(String filename, byte[] bytes) {

    /** One file never holds more rows than this; ask for a narrower range instead. */
    public static final int MAX_ROWS = 20_000;

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";

    public CsvExport {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Fails before any row is read when the filters match more than {@link #MAX_ROWS}. */
    public static void checkRowCount(long rows) {
        if (rows > MAX_ROWS) {
            throw new BadRequestException(ErrorCode.EXPORT_TOO_LARGE,
                    "The filters match " + rows + " rows; one file holds at most " + MAX_ROWS,
                    Map.of("rows", rows, "maxRows", MAX_ROWS));
        }
    }

    public ResponseEntity<byte[]> toResponse() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(bytes);
    }

    public static Builder builder(String... header) {
        return new Builder(header);
    }

    public static final class Builder {

        private final StringBuilder out = new StringBuilder("﻿");
        private final int columns;

        private Builder(String... header) {
            columns = header.length;
            append((Object[]) header);
        }

        /** Cells are text unless they are a {@link Number}; null is an empty cell. */
        public Builder row(Object... cells) {
            if (cells.length != columns) {
                throw new IllegalArgumentException("expected " + columns + " cells, got " + cells.length);
            }
            append(cells);
            return this;
        }

        public CsvExport build(String filename) {
            return new CsvExport(filename, out.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void append(Object... cells) {
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('"').append(cell(cells[i]).replace("\"", "\"\"")).append('"');
            }
            out.append("\r\n");
        }
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return guardFormula(value.toString());
    }

    /** CSV injection (CWE-1236): a leading = + - @ tab or CR makes Excel treat the cell as a formula. */
    static String guardFormula(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return switch (text.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "'" + text;
            default -> text;
        };
    }
}
