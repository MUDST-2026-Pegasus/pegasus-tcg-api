package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.CatalogCardEffectResponse;
import com.pegasus.pegasustcgapi.repository.CatalogCardEffectRepository;
import com.pegasus.pegasustcgapi.service.CatalogProductService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public, sourced card rules for a catalog product. */
@RestController
public class CatalogCardEffectController {

    private final CatalogProductService products;
    private final CatalogCardEffectRepository effects;

    public CatalogCardEffectController(CatalogProductService products, CatalogCardEffectRepository effects) {
        this.products = products;
        this.effects = effects;
    }

    @GetMapping(ApiPaths.CATALOG + "/products/{idOrSlug}/effects")
    public ApiResult<List<CatalogCardEffectResponse>> effects(@PathVariable("idOrSlug") String idOrSlug) {
        long productId = products.requireActiveByIdOrSlug(idOrSlug).id();
        return ApiResult.success(effects.findByProductId(productId));
    }
}
