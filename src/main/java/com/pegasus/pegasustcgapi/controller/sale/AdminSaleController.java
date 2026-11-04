package com.pegasus.pegasustcgapi.controller.sale;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleRequest;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.AdminSale;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.AdminSalePage;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.SaleListingRow;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.ScopePreview;
import com.pegasus.pegasustcgapi.dto.sale.AdminSaleViews.Summary;
import com.pegasus.pegasustcgapi.dto.sale.SaleActionRequests;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.OwnerType;
import com.pegasus.pegasustcgapi.model.sale.PriceSale.SaleType;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.sale.AdminSaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sales (Admin)")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.ADMIN + "/sales")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSaleController {

    private final AdminSaleService sales;

    public AdminSaleController(AdminSaleService sales) {
        this.sales = sales;
    }

    @Operation(summary = "List price sales",
            description = "Pegasus campaigns and shop sales, newest first. q matches the name, the slug or the "
                    + "shop. statusCounts covers every status under the same owner, type and q, for the tabs.")
    @GetMapping
    public ApiResult<AdminSalePage> list(
            @RequestParam(required = false) OwnerType owner,
            @RequestParam(required = false) SaleType type,
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(sales.list(owner, type, status, q, page, size));
    }

    @Operation(summary = "Sale KPIs",
            description = "Running sales by owner, listings on sale now, and sale sales and SALE_SUBSIDY over "
                    + "the last 30 days.")
    @GetMapping("/summary")
    public ApiResult<Summary> summary() {
        return ApiResult.success(sales.summary());
    }

    @Operation(summary = "Count what a scope reaches",
            description = "ACTIVE listings, shops and stock value at list price the scope would cover if the "
                    + "sale opened now.")
    @PostMapping("/preview-scope")
    public ApiResult<ScopePreview> previewScope(@Valid @RequestBody SaleActionRequests.PreviewScope request) {
        return ApiResult.success(sales.previewScope(request));
    }

    @Operation(summary = "Get a sale")
    @GetMapping("/{id}")
    public ApiResult<AdminSale> get(@PathVariable long id) {
        return ApiResult.success(sales.get(id));
    }

    @Operation(summary = "Listings a sale reaches",
            description = "Listings selling at its price now, plus flash listings whose quota ran out, "
                    + "with sold and quota left.")
    @GetMapping("/{id}/listings")
    public ApiResult<PageResponse<SaleListingRow>> listings(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.success(sales.listings(id, page, size));
    }

    @Operation(summary = "Create a Pegasus sale",
            description = "FLASH_SALE (at most 72 hours, optional quota and per-buyer limit) or CATEGORY_SALE. "
                    + "400 SALE_INVALID with a reason when the form breaks a rule or the slug is taken.")
    @PostMapping
    public ResponseEntity<ApiResult<AdminSale>> create(@Valid @RequestBody AdminSaleRequest request,
            AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(sales.create(request, principal.userId())));
    }

    @Operation(summary = "Replace the home-page sales",
            description = "Up to 8 Pegasus sales that have not ended, at most one flash sale, in display order. "
                    + "Featured sales left out are taken off the home page.")
    @PutMapping("/home-order")
    public ApiResult<List<AdminSale>> homeOrder(@Valid @RequestBody SaleActionRequests.HomeOrder request,
            AuthPrincipal principal) {
        return ApiResult.success(sales.replaceHomeOrder(request.saleIds(), principal.userId()));
    }

    @Operation(summary = "Update a Pegasus sale",
            description = "Send the whole form. Once something has sold at its price, only the name, end date, "
                    + "banner, home page, a higher budget and a higher quota may change (else 400 SALE_INVALID). "
                    + "Shop sales are 403.")
    @PutMapping("/{id}")
    public ApiResult<AdminSale> update(@PathVariable long id, @Valid @RequestBody AdminSaleRequest request,
            AuthPrincipal principal) {
        return ApiResult.success(sales.update(id, request, principal.userId()));
    }

    @Operation(summary = "Delete a Pegasus sale",
            description = "Only a sale nothing has sold at; else 409 SALE_IN_USE. Shop sales are 403.")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, AuthPrincipal principal) {
        sales.delete(id, principal.userId());
        return ApiResult.success("Sale deleted", null);
    }

    @Operation(summary = "Pause a Pegasus or shop sale",
            description = "A reason is required for a shop's sale; the shop sees it. Replaces the shop's own pause.")
    @PostMapping("/{id}/pause")
    public ApiResult<AdminSale> pause(@PathVariable long id,
            @Valid @RequestBody SaleActionRequests.Pause request, AuthPrincipal principal) {
        return ApiResult.success(sales.pause(id, request, principal.userId()));
    }

    @Operation(summary = "Resume a paused sale")
    @PostMapping("/{id}/resume")
    public ApiResult<AdminSale> resume(@PathVariable long id,
            @Valid @RequestBody SaleActionRequests.Resume request, AuthPrincipal principal) {
        return ApiResult.success(sales.resume(id, request, principal.userId()));
    }

    @Operation(summary = "End a Pegasus sale now",
            description = "Listings go back to their own price at once and it leaves the home page. "
                    + "400 SALE_INVALID before it starts; shop sales are 403.")
    @PostMapping("/{id}/end")
    public ApiResult<AdminSale> end(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(sales.end(id, principal.userId()));
    }
}
