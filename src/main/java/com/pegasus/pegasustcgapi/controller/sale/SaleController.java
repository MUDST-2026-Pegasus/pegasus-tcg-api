package com.pegasus.pegasustcgapi.controller.sale;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.Featured;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SaleHeader;
import com.pegasus.pegasustcgapi.dto.sale.BuyerSaleViews.SalePage;
import com.pegasus.pegasustcgapi.model.catalog.CardCondition;
import com.pegasus.pegasustcgapi.service.sale.SaleBrowseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The sale pages, open to guests (SAL-B1/B2) [TCG-679]. */
@RestController
@RequestMapping(ApiPaths.SALES)
@Tag(name = "Sales", description = "Direct price discounts as buyers see them")
public class SaleController {

    private final SaleBrowseService sales;

    public SaleController(SaleBrowseService sales) {
        this.sales = sales;
    }

    @GetMapping
    @Operation(summary = "Pegasus campaigns running now, and flash sales starting within 24 hours")
    public ApiResult<List<SaleHeader>> open() {
        return ApiResult.success(sales.open());
    }

    /** Declared before /{slug} so "featured" is never read as a slug. */
    @GetMapping("/featured")
    @Operation(summary = "The home page's flash sale (12 cards) and featured campaigns; null when there are none")
    public ApiResult<Featured> featured() {
        return ApiResult.success(sales.featured());
    }

    @GetMapping("/{slug}")
    @Operation(summary = "A sale's header and a page of its cards, filtered and sorted like GET /listings")
    public ApiResult<SalePage> page(
            @PathVariable String slug,
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Short gameId,
            @RequestParam(required = false) CardCondition condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minDiscount,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ApiResult.success(sales.page(slug, variantId, productId, gameId, condition, minPrice, maxPrice,
                minDiscount, sort, page, size));
    }
}
