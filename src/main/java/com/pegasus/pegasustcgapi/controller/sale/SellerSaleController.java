package com.pegasus.pegasustcgapi.controller.sale;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleRequest;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.PlatformListing;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.SellerSale;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.SellerSalePage;
import com.pegasus.pegasustcgapi.dto.sale.SellerSaleViews.Summary;
import com.pegasus.pegasustcgapi.model.sale.SaleStatus;
import com.pegasus.pegasustcgapi.security.AuthPrincipal;
import com.pegasus.pegasustcgapi.service.sale.SellerSaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in shop's own sales [TCG-678]. Another shop's sale, or Pegasus's, is 404. */
@Tag(name = "Sales (Seller)", description = "A shop lowering prices on its own listings, paid for by the shop")
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping(ApiPaths.SELLERS_ME + "/sales")
public class SellerSaleController {

    private final SellerSaleService sales;

    public SellerSaleController(SellerSaleService sales) {
        this.sales = sales;
    }

    @Operation(summary = "List the shop's sales",
            description = "Newest first. statusCounts covers every status, for the tabs.")
    @GetMapping
    public ApiResult<SellerSalePage> list(
            @RequestParam(required = false) SaleStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(sales.list(principal.userId(), status, page, size));
    }

    @Operation(summary = "The shop's sale KPIs",
            description = "Sales running now, listings on the shop's sale prices now, and what buyers paid and "
                    + "the shop gave away at those prices over the last 30 days.")
    @GetMapping("/summary")
    public ApiResult<Summary> summary(AuthPrincipal principal) {
        return ApiResult.success(sales.summary(principal.userId()));
    }

    @Operation(summary = "The shop's listings in Pegasus campaigns",
            description = "Listings selling at a Pegasus campaign's price now; their price cannot go up until it ends.")
    @GetMapping("/platform-listings")
    public ApiResult<PageResponse<PlatformListing>> platformListings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthPrincipal principal) {
        return ApiResult.success(sales.platformListings(principal.userId(), page, size));
    }

    @Operation(summary = "Get one of the shop's sales",
            description = "A LISTINGS sale lists its listings, each with any Pegasus campaign that also covers it "
                    + "and which price wins.")
    @GetMapping("/{id}")
    public ApiResult<SellerSale> get(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(sales.get(principal.userId(), id));
    }

    @Operation(summary = "Create a shop sale",
            description = "SHOP, CATEGORY or LISTINGS. The shop must be VERIFIED (403 SELLER_NOT_VERIFIED). "
                    + "400 SALE_INVALID with details.rows for listings that cannot take it; 409 SALE_OVERLAP with "
                    + "details.listingIds when another sale of the shop covers them at that time.")
    @PostMapping
    public ResponseEntity<ApiResult<SellerSale>> create(@Valid @RequestBody SellerSaleRequest request,
            AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(sales.create(principal.userId(), request)));
    }

    @Operation(summary = "Update a shop sale",
            description = "Send the whole form. Once something has sold at its price only the name and end date "
                    + "may change (else 400 SALE_INVALID).")
    @PutMapping("/{id}")
    public ApiResult<SellerSale> update(@PathVariable long id, @Valid @RequestBody SellerSaleRequest request,
            AuthPrincipal principal) {
        return ApiResult.success(sales.update(principal.userId(), id, request));
    }

    @Operation(summary = "Delete a shop sale",
            description = "Only a sale nothing has sold at; else 409 SALE_IN_USE.")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id, AuthPrincipal principal) {
        sales.delete(principal.userId(), id);
        return ApiResult.success("Sale deleted", null);
    }

    @Operation(summary = "Pause a shop sale")
    @PostMapping("/{id}/pause")
    public ApiResult<SellerSale> pause(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(sales.pause(principal.userId(), id));
    }

    @Operation(summary = "Resume a shop sale",
            description = "409 SALE_PAUSED with details.reason when Pegasus paused it; only Pegasus can resume it then.")
    @PostMapping("/{id}/resume")
    public ApiResult<SellerSale> resume(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(sales.resume(principal.userId(), id));
    }

    @Operation(summary = "End a shop sale now",
            description = "Listings go back to their own price at once. 400 SALE_INVALID before it starts.")
    @PostMapping("/{id}/end")
    public ApiResult<SellerSale> end(@PathVariable long id, AuthPrincipal principal) {
        return ApiResult.success(sales.end(principal.userId(), id));
    }
}
