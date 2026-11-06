package com.pegasus.pegasustcgapi.service.metrics;

import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.DailySales;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.GameBreakdown;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Summary;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.DailyAmounts;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameProfit;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameStock;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.Totals;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds an admin weekly report using Bangkok calendar weeks. */
@Service
public class AdminWeeklyStockProfitService {

    static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final AdminWeeklyStockProfitRepository repository;

    public AdminWeeklyStockProfitService(AdminWeeklyStockProfitRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Report report(String startDate, String endDate, String gameCategory, String productType) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start.getDayOfWeek().getValue() != 1 || !end.equals(start.plusDays(6))) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "startDate must be Monday and endDate must be the following Sunday in Bangkok time");
        }
        String game = normalizeGame(gameCategory);
        String type = normalizeType(productType);
        OffsetDateTime from = start.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = start.plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime();
        Totals totals = repository.completedTotals(from, to, game, type);
        Totals previous = repository.completedTotals(from.minusWeeks(1), from, game, type);
        BigDecimal wow = percentChange(totals.gmv(), previous.gmv());

        Map<LocalDate, DailyAmounts> days = new HashMap<>();
        repository.dailyAmounts(from, to, game, type).forEach(row -> days.put(row.date(), row));
        List<DailySales> chart = new ArrayList<>(7);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            DailyAmounts row = days.get(date);
            chart.add(new DailySales(date,
                    row == null ? BigDecimal.ZERO : row.singles(),
                    row == null ? BigDecimal.ZERO : row.sealed()));
        }

        long stock = repository.activeStock(to, game, type);
        long depleted = repository.completedQuantity(from, to, game, type);
        Map<String, GameStock> stockByGame = new HashMap<>();
        repository.activeStockByGame(to, game, type).forEach(row -> stockByGame.put(row.slug(), row));
        Map<String, GameProfit> profitByGame = new HashMap<>();
        repository.profitByGame(from, to, game, type).forEach(row -> profitByGame.put(row.slug(), row));
        Map<String, GameBreakdown> rawBreakdown = new HashMap<>();
        stockByGame.forEach((slug, row) -> rawBreakdown.put(slug, new GameBreakdown(row.name(), row.stock(),
                null, profitByGame.containsKey(slug) ? profitByGame.get(slug).profit() : BigDecimal.ZERO, null)));
        profitByGame.forEach((slug, row) -> {
            if (!stockByGame.containsKey(slug)) {
                rawBreakdown.put(slug, new GameBreakdown(row.name(), 0, null, row.profit(), null));
            }
        });
        long allStock = rawBreakdown.values().stream().mapToLong(GameBreakdown::remainingStock).sum();
        BigDecimal allProfit = rawBreakdown.values().stream().map(GameBreakdown::profit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<GameBreakdown> breakdown = rawBreakdown.values().stream()
                .map(row -> new GameBreakdown(row.game(), row.remainingStock(),
                        percentShare(BigDecimal.valueOf(row.remainingStock()), BigDecimal.valueOf(allStock)),
                        row.profit(), percentShare(row.profit(), allProfit)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        breakdown.sort((a, b) -> a.game().compareToIgnoreCase(b.game()));

        return new Report(start, end, new Summary(totals.gmv(), totals.commission(), totals.netProfit(), stock, depleted, wow),
                List.copyOf(chart), List.copyOf(breakdown));
    }

    static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).multiply(HUNDRED).divide(previous, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentShare(BigDecimal value, BigDecimal total) {
        if (total.signum() == 0) return null;
        return value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "startDate and endDate are required");
        }
        try {
            return OffsetDateTime.parse(value.trim()).atZoneSameInstant(ZONE).toLocalDate();
        } catch (DateTimeParseException notDateTime) {
            try {
                return LocalDateTime.parse(value.trim()).atZone(ZONE).toLocalDate();
            } catch (DateTimeParseException notLocalDateTime) {
                try {
                    return LocalDate.parse(value.trim());
                } catch (DateTimeParseException invalid) {
                    throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "dates must be ISO 8601 values");
                }
            }
        }
    }

    private static String normalizeGame(String game) {
        if (game == null || game.isBlank()) return null;
        String normalized = game.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9-]{1,100}")) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "gameCategory must be a game slug");
        }
        return normalized;
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) return null;
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "single_card" -> "SINGLE_CARD";
            case "sealed_box" -> "SEALED";
            default -> throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "productType must be single_card or sealed_box");
        };
    }

    /** CSV is UTF-8 with a BOM for Excel; XLSX is a small standards-compliant workbook. */
    public ExportFile export(Report report, String format) {
        String normalized = format == null ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        int week = report.startDate().get(WeekFields.ISO.weekOfWeekBasedYear());
        int year = report.startDate().get(WeekFields.ISO.weekBasedYear());
        String base = "pegasus_weekly_report_%04d_%02d".formatted(year, week);
        if (normalized.equals("csv")) {
            StringBuilder csv = new StringBuilder("\uFEFFsection,date,game,singles,sealed,gmv,commission,netProfit,remainingStock,stockDepleted,wowChangePercent\r\n");
            for (DailySales d : report.chartData()) {
                csvRow(csv, "daily", d.date().toString(), "", d.singles().toPlainString(),
                        d.sealed().toPlainString(), "", "", "", "", "", "");
            }
            Summary s = report.summary();
            csvRow(csv, "summary", "", "", "", "", s.gmv().toPlainString(),
                    s.commission().toPlainString(), s.netProfit().toPlainString(),
                    Long.toString(s.remainingStock()), Long.toString(s.stockDepleted()),
                    s.wowChangePercent() == null ? "" : s.wowChangePercent().toPlainString());
            for (GameBreakdown game : report.breakdownByGame()) {
                csvRow(csv, "game", "", game.game(), "", "", "", "",
                        game.profit().toPlainString(), Long.toString(game.remainingStock()), "", "");
            }
            return new ExportFile(base + ".csv", "text/csv; charset=UTF-8", csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (normalized.equals("xlsx")) {
            return new ExportFile(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook(report));
        }
        throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "format must be csv or xlsx");
    }

    private static String escapeCsv(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static void csvRow(StringBuilder csv, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            csv.append(escapeCsv(values[i]));
        }
        csv.append("\r\n");
    }

    private static byte[] workbook(Report report) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Weekly Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            int row = 1;
            row = textRow(sheet, row, "Section", "Date", "Game", "Singles", "Sealed", "GMV", "Commission", "Net profit", "Remaining stock", "Stock depleted", "WoW change %");
            Summary s = report.summary();
            row = numberRow(sheet, row, "summary", "", "", "", "", s.gmv(), s.commission(), s.netProfit(), s.remainingStock(), s.stockDepleted(), s.wowChangePercent());
            for (DailySales d : report.chartData()) row = numberRow(sheet, row, "daily", d.date().toString(), "", d.singles(), d.sealed(), null, null, null, null, null);
            for (GameBreakdown g : report.breakdownByGame()) row = textThenNumbers(sheet, row, g.game(), g.remainingStock(), g.profit());
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not build XLSX", impossible);
        }
    }

    private static int textRow(StringBuilder xml, int row, String... values) {
        xml.append("<row r=\"").append(row).append("\">");
        for (int col = 0; col < values.length; col++) inlineCell(xml, col, row, values[col]);
        xml.append("</row>");
        return row + 1;
    }

    private static int numberRow(StringBuilder xml, int row, Object... values) {
        xml.append("<row r=\"").append(row).append("\">");
        for (int col = 0; col < values.length; col++) {
            Object v = values[col];
            if (v == null) continue;
            if (v instanceof Number) {
                xml.append("<c r=\"").append(colName(col)).append(row).append("\"><v>").append(v).append("</v></c>");
            } else inlineCell(xml, col, row, v.toString());
        }
        xml.append("</row>");
        return row + 1;
    }

    private static int textThenNumbers(StringBuilder xml, int row, String game, long stock, BigDecimal profit) {
        xml.append("<row r=\"").append(row).append("\">");
        inlineCell(xml, 0, row, "game"); inlineCell(xml, 2, row, game);
        xml.append("<c r=\"I").append(row).append("\"><v>").append(stock).append("</v></c>");
        xml.append("<c r=\"H").append(row).append("\"><v>").append(profit).append("</v></c></row>");
        return row + 1;
    }

    private static void inlineCell(StringBuilder xml, int col, int row, String value) {
        xml.append("<c r=\"").append(colName(col)).append(row).append("\" t=\"inlineStr\"><is><t>")
                .append(value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                .append("</t></is></c>");
    }

    private static String colName(int index) {
        StringBuilder name = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) name.insert(0, (char) ('A' + (n - 1) % 26));
        return name.toString();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    public record ExportFile(String filename, String contentType, byte[] bytes) {}
}
