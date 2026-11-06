package com.pegasus.pegasustcgapi.controller.metrics;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.metrics.WeeklyStockProfitViews.Report;
import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitService;
import com.pegasus.pegasustcgapi.service.metrics.AdminWeeklyStockProfitService.ExportFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    @Operation(summary = "Export the weekly stock and profit report as CSV or XLSX")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String startDate, @RequestParam String endDate,
            @RequestParam(required = false) String gameCategory,
            @RequestParam(required = false) String productType,
            @RequestParam(defaultValue = "csv") String format) {
        Report report = reports.report(startDate, endDate, gameCategory, productType);
        ExportFile file = reports.export(report, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.bytes());
    }
}
