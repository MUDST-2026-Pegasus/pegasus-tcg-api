package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.ProductCollectorResponse;
import com.pegasus.pegasustcgapi.repository.ProductCollectorRepository;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The collectors who chose to show a product in their public collection. */
@RestController
public class ProductCollectorController {

    private final CatalogProductService products;
    private final ProductCollectorRepository collectors;
    private final StorageService storage;

    public ProductCollectorController(
            CatalogProductService products,
            ProductCollectorRepository collectors,
            StorageService storage) {
        this.products = products;
        this.collectors = collectors;
        this.storage = storage;
    }

    @GetMapping(ApiPaths.CATALOG + "/products/{idOrSlug}/collectors")
    public ApiResult<PageResponse<ProductCollectorResponse>> collectors(
            @PathVariable String idOrSlug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        long productId = products.requireActiveByIdOrSlug(idOrSlug).id();
        PageResponse<ProductCollectorResponse> pageResult = collectors.findByProduct(productId, page, size);
        List<ProductCollectorResponse> signed = pageResult.items().stream()
                .map(collector -> new ProductCollectorResponse(
                        collector.username(),
                        collector.displayName(),
                        presign(collector.avatarUrl()),
                        collector.quantity()))
                .toList();
        return ApiResult.success(PageResponse.of(signed, pageResult.page(), pageResult.size(), pageResult.totalItems()));
    }

    private String presign(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key;
        }
        return storage.presignDownload(key);
    }
}
