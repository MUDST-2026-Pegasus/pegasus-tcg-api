package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductCollectorResponse;
import com.pegasus.pegasustcgapi.repository.ProductCollectorRepository;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The collectors who chose to show a product in their public collection. */
@RestController
public class ProductCollectorController {

    private final CatalogProductService products;
    private final ProductCollectorRepository collectors;

    public ProductCollectorController(CatalogProductService products, ProductCollectorRepository collectors) {
        this.products = products;
        this.collectors = collectors;
    }

    @GetMapping(ApiPaths.CATALOG + "/products/{idOrSlug}/collectors")
    public ApiResult<PageResponse<ProductCollectorResponse>> collectors(
            @PathVariable String idOrSlug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        long productId = products.requireActiveByIdOrSlug(idOrSlug).id();
        return ApiResult.success(collectors.findByProduct(productId, page, size));
    }
}
