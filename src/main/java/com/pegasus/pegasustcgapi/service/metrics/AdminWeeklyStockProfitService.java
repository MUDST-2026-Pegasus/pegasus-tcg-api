package com.pegasus.pegasustcgapi.service.metrics;

import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.BreakdownRow;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.BreakdownTotals;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.DailySales;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.GameBreakdown;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.GameDistributionItem;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.ReportMeta;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.StockThresholds;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.StockThresholdsUpdate;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Summary;
import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.DailyAmounts;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameBreakdownStat;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameProfit;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.GameStock;
import com.pegasus.pegasustcgapi.repository.metrics.AdminWeeklyStockProfitRepository.Totals;
import com.pegasus.pegasustcgapi.service.setting.PlatformSettingService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
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

    public static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    public static final BigDecimal COMMISSION_RATE_PERCENT = new BigDecimal("3.0");
    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.03");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO_ONE_DECIMAL = new BigDecimal("0.0");

    private final AdminWeeklyStockProfitRepository repository;
    private final PlatformSettingService settings;
    private final Clock clock;

    public AdminWeeklyStockProfitService(AdminWeeklyStockProfitRepository repository,
            PlatformSettingService settings, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StockThresholds thresholds() {
        return new StockThresholds(
                settings.getInt(PlatformSettingService.REPORT_LOW_STOCK_THRESHOLD),
                settings.getInt(PlatformSettingService.REPORT_FAST_MOVING_PERCENT),
                settings.getDecimal(PlatformSettingService.COMMISSION_DEFAULT_RATE));
    }

    @Transactional
    public StockThresholds updateThresholds(StockThresholdsUpdate update, long adminId) {
        settings.update(PlatformSettingService.REPORT_LOW_STOCK_THRESHOLD,
                Integer.toString(update.lowStockThreshold().intValueExact()), adminId);
        settings.update(PlatformSettingService.REPORT_FAST_MOVING_PERCENT,
                Integer.toString(update.fastMovingPercent().intValueExact()), adminId);
        return thresholds();
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

        Map<LocalDate, DailyAmounts> days = new HashMap<>();
        repository.dailyAmounts(from, to, game, type).forEach(row -> days.put(row.date(), row));
        List<DailySales> chart = new ArrayList<>(7);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            DailyAmounts row = days.get(date);
            chart.add(new DailySales(date,
                    row == null ? BigDecimal.ZERO : row.singles(),
                    row == null ? BigDecimal.ZERO : row.sealed(),
                    row == null ? BigDecimal.ZERO : row.other()));
        }

        // A week that has not started has no stock to report; the latest history row would
        // otherwise present today's listings as that future week's closing stock.
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        long rawStock = start.isAfter(today) ? 0 : repository.activeStock(to, game, type);
        long depleted = repository.completedQuantity(from, to, game, type);
        Map<String, GameStock> stockByGame = new HashMap<>();
        repository.activeStockByGame(to, game, type).forEach(row -> stockByGame.put(row.slug(), row));
        Map<String, GameProfit> profitByGame = new HashMap<>();
        repository.profitByGame(from, to, game, type).forEach(row -> profitByGame.put(row.slug(), row));

        boolean hasData = totals.gmv().signum() > 0 || depleted > 0 || rawStock > 0;
        ReportMeta meta = new ReportMeta(start, end, ZONE.getId(), game, type, COMMISSION_RATE_PERCENT);

        if (!hasData) {
            Summary emptySummary = new Summary(
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0, 0, 0, 0, ZERO_ONE_DECIMAL);
            return new Report(start, end, false, meta, emptySummary,
                    List.copyOf(chart), List.of(), List.of(), List.of(), BreakdownTotals.zero());
        }

        BigDecimal wow = percentChange(totals.gmv(), previous.gmv());
        long stock = rawStock;

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

        List<GameBreakdownStat> detailedRows = repository.gameBreakdownRows(from, to, game, type);
        StockThresholds thresholds = thresholds();
        List<BreakdownRow> breakdownTable = buildBreakdownTable(detailedRows, rawBreakdown, depleted, totals, thresholds);
        BreakdownTotals tableTotals = buildBreakdownTotals(breakdownTable, stock, depleted, totals);
        List<GameDistributionItem> gameDistribution = buildGameDistribution(breakdownTable, tableTotals);

        Summary summary = new Summary(
                totals.gmv(), totals.gmv(),
                totals.commission(), totals.commission(),
                totals.netProfit(),
                stock + depleted, depleted, stock, depleted, wow);

        return new Report(start, end, true, meta, summary,
                List.copyOf(chart), List.copyOf(breakdown),
                List.copyOf(gameDistribution), List.copyOf(breakdownTable), tableTotals);
    }

    private static List<BreakdownRow> buildBreakdownTable(
            List<GameBreakdownStat> detailedRows,
            Map<String, GameBreakdown> fallbackBreakdown,
            long totalDepleted,
            Totals totals,
            StockThresholds thresholds) {
        List<BreakdownRow> rows = new ArrayList<>();
        if (detailedRows != null && !detailedRows.isEmpty()) {
            for (GameBreakdownStat stat : detailedRows) {
                long remaining = stat.remainingStock();
                long sold = stat.soldCount();
                long initial = calculateInitialStock(remaining, sold);
                String status = classifyStockStatus(initial, sold, remaining,
                        thresholds.lowStockThreshold(), thresholds.fastMovingPercent());
                rows.add(new BreakdownRow(
                        stat.name(),
                        stat.slug(),
                        initial,
                        sold,
                        remaining,
                        stat.singleCards(),
                        stat.sealedBoxes(),
                        stat.otherItems(),
                        stat.gmv(),
                        stat.commission(),
                        stat.profit(),
                        status,
                        statusLabel(status)));
            }
        } else if (!fallbackBreakdown.isEmpty()) {
            int count = fallbackBreakdown.size();
            for (Map.Entry<String, GameBreakdown> entry : fallbackBreakdown.entrySet()) {
                GameBreakdown gb = entry.getValue();
                long remaining = gb.remainingStock();
                long sold = count == 1 ? totalDepleted : 0;
                long initial = calculateInitialStock(remaining, sold);
                BigDecimal rowGmv = count == 1 ? totals.gmv() : gb.profit();
                BigDecimal rowCommission = count == 1 ? totals.commission() : calculateCommission(rowGmv);
                BigDecimal rowProfit = gb.profit();
                String status = classifyStockStatus(initial, sold, remaining,
                        thresholds.lowStockThreshold(), thresholds.fastMovingPercent());
                rows.add(new BreakdownRow(
                        gb.game(),
                        entry.getKey(),
                        initial,
                        sold,
                        remaining,
                        remaining,
                        0,
                        0,
                        rowGmv,
                        rowCommission,
                        rowProfit,
                        status,
                        statusLabel(status)));
            }
        }
        rows.sort(Comparator.comparing(BreakdownRow::gmv, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(BreakdownRow::remainingStock).reversed())
                .thenComparing(BreakdownRow::game, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private static BreakdownTotals buildBreakdownTotals(
            List<BreakdownRow> rows, long fallbackStock, long fallbackDepleted, Totals fallbackTotals) {
        if (rows.isEmpty()) {
            return new BreakdownTotals(
                    fallbackStock + fallbackDepleted,
                    fallbackDepleted,
                    fallbackStock,
                    fallbackStock,
                    0,
                    0,
                    fallbackTotals.gmv(),
                    fallbackTotals.gmv(),
                    fallbackTotals.commission(),
                    fallbackTotals.commission(),
                    fallbackTotals.netProfit());
        }
        long initialStock = 0;
        long soldCount = 0;
        long remainingStock = 0;
        long singleCards = 0;
        long sealedBoxes = 0;
        long otherItems = 0;
        BigDecimal gmv = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal netProfit = BigDecimal.ZERO;
        for (BreakdownRow row : rows) {
            initialStock += row.initialStock();
            soldCount += row.soldCount();
            remainingStock += row.remainingStock();
            singleCards += row.singleCards();
            sealedBoxes += row.sealedBoxes();
            otherItems += row.otherItems();
            gmv = gmv.add(row.gmv());
            commission = commission.add(row.commission());
            netProfit = netProfit.add(row.netProfit());
        }
        return new BreakdownTotals(
                initialStock, soldCount, remainingStock, singleCards, sealedBoxes, otherItems,
                gmv, gmv, commission, commission, netProfit);
    }

    private static List<GameDistributionItem> buildGameDistribution(
            List<BreakdownRow> rows, BreakdownTotals totals) {
        List<GameDistributionItem> dist = new ArrayList<>(rows.size());
        for (BreakdownRow row : rows) {
            BigDecimal pct;
            if (totals.gmv().signum() > 0) {
                pct = percentShare(row.gmv(), totals.gmv());
            } else if (totals.remainingStock() > 0) {
                pct = percentShare(BigDecimal.valueOf(row.remainingStock()),
                        BigDecimal.valueOf(totals.remainingStock()));
            } else {
                pct = BigDecimal.ZERO;
            }
            dist.add(new GameDistributionItem(
                    row.game(),
                    row.gameSlug(),
                    row.gmv(),
                    row.netProfit(),
                    row.remainingStock(),
                    pct == null ? BigDecimal.ZERO : pct));
        }
        return dist;
    }

    public static BigDecimal calculateCommission(BigDecimal gmv) {
        if (gmv == null || gmv.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return gmv.multiply(COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculatePlatformNetProfit(BigDecimal gmv) {
        return calculateCommission(gmv);
    }

    public static long calculateRemainingStock(long initialStock, long soldCount) {
        if (initialStock < 0 || soldCount < 0 || soldCount > initialStock) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "initialStock and soldCount must be non-negative with soldCount <= initialStock");
        }
        return initialStock - soldCount;
    }

    public static long calculateInitialStock(long remainingStock, long soldCount) {
        return remainingStock + soldCount;
    }

    public static String classifyStockStatus(long initialStock, long soldCount, long remainingStock,
            int lowStockThreshold, int fastMovingPercent) {
        if (remainingStock <= 0 || remainingStock < lowStockThreshold) {
            return "LOW_STOCK";
        }
        if (soldCount * 100 > fastMovingPercent * initialStock) {
            return "FAST_MOVING";
        }
        return "NORMAL";
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case "LOW_STOCK" -> "สต็อกต่ำ";
            case "FAST_MOVING" -> "สต็อกหมุนเวียนเร็ว";
            default -> "ปกติ";
        };
    }

    public static boolean isWithinBangkokWeek(OffsetDateTime timestamp, LocalDate mondayStart) {
        if (timestamp == null || mondayStart == null) {
            return false;
        }
        OffsetDateTime from = mondayStart.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = mondayStart.plusWeeks(1).atStartOfDay(ZONE).toOffsetDateTime();
        return !timestamp.isBefore(from) && timestamp.isBefore(to);
    }

    public record OrderSample(String status, BigDecimal gmv, OffsetDateTime completedAt) {}

    public static Totals calculateCompletedMetrics(
            List<OrderSample> orders, LocalDate mondayStart) {
        if (orders == null || orders.isEmpty()) {
            return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal gmv = BigDecimal.ZERO;
        for (OrderSample order : orders) {
            if (order != null
                    && "COMPLETED".equalsIgnoreCase(order.status())
                    && isWithinBangkokWeek(order.completedAt(), mondayStart)
                    && order.gmv() != null) {
                gmv = gmv.add(order.gmv());
            }
        }
        BigDecimal commission = calculateCommission(gmv);
        return new Totals(gmv, commission, commission);
    }

    public static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return ZERO_ONE_DECIMAL;
        }
        return current.subtract(previous).multiply(HUNDRED).divide(previous, 1, RoundingMode.HALF_UP);
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
            case "other" -> "OTHER";
            default -> throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "productType must be single_card, sealed_box or other");
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
