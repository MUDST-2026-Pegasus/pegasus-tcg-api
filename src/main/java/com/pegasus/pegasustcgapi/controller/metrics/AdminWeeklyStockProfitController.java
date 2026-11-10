package com.pegasus.pegasustcgapi.controller.metrics;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.StockThresholds;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.StockThresholdsUpdate;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitService;
import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitService.ExportDataset;
import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitService.ExportFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Weekly Reports (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(ApiPaths.ADMIN + "/reports/weekly-stock-profit")
public class AdminWeeklyStockProfitController {

    private final AdminWeeklyStockProfitService reports;

    public AdminWeeklyStockProfitController(AdminWeeklyStockProfitService reports) {
        this.reports = reports;
    }

    @Operation(summary = "Weekly stock and profit report",
            description = "Dates must identify one Monday-to-Sunday week in Asia/Bangkok. Profit and "
                    + "commission include completed seller orders only.")
    @GetMapping
    public ApiResult<Report> report(@RequestParam String startDate, @RequestParam String endDate,
            @RequestParam(required = false) String gameCategory,
            @RequestParam(required = false) String productType) {
        return ApiResult.success(reports.report(startDate, endDate, gameCategory, productType));
    }

    @Operation(summary = "Read the stock status thresholds",
            description = "Also returns the standard commission rate, which is changed from the "
                    + "commission settings rather than here.")
    @GetMapping("/thresholds")
    public ApiResult<StockThresholds> thresholds() {
        return ApiResult.success(reports.thresholds());
    }

    @Operation(summary = "Save the stock status thresholds",
            description = "A row is low stock when remaining units are below lowStockThreshold, otherwise "
                    + "fast moving when more than fastMovingPercent of its stock sold this week.")
    @PutMapping("/thresholds")
    public ApiResult<StockThresholds> updateThresholds(@Valid @RequestBody StockThresholdsUpdate request,
            AuthPrincipal principal) {
        return ApiResult.success("Thresholds updated", reports.updateThresholds(request, principal.userId()));
    }

    @Operation(summary = "Export the weekly stock and profit report as CSV or XLSX",
            description = "datasets picks what the file carries: kpi (summary and daily rows), breakdown "
                    + "(game and product-type tables) or both, comma-separated. Omit it for both.")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String startDate, @RequestParam String endDate,
            @RequestParam(required = false) String gameCategory,
            @RequestParam(required = false) String productType,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String datasets) {
        Set<ExportDataset> selected = AdminWeeklyStockProfitService.parseExportDatasets(datasets);
        Report report = reports.report(startDate, endDate, gameCategory, productType);
        ExportFile file = reports.export(report, format, selected);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.bytes());
    }
}
