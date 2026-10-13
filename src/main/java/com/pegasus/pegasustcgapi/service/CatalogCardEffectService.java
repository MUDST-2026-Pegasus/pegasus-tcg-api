package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CatalogCardEffectResponse;
import com.pegasus.pegasustcgapi.repository.CatalogCardEffectRepository;
import com.pegasus.pegasustcgapi.repository.CatalogCardEffectRepository.EffectFields;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A card's abilities, attacks and printed rules as admins maintain them. The
 * whole list is saved at once, so the order shown on the card page is exactly
 * the order the admin left it in.
 */
@Service
public class CatalogCardEffectService {

    private static final Logger log = LoggerFactory.getLogger(CatalogCardEffectService.class);

    private final CatalogCardEffectRepository effects;
    private final CatalogProductService products;

    public CatalogCardEffectService(CatalogCardEffectRepository effects, CatalogProductService products) {
        this.effects = effects;
        this.products = products;
    }

    /** Found for retired products too, so an admin can still correct them. */
    public List<CatalogCardEffectResponse> listOfProduct(long productId) {
        products.require(productId);
        return effects.findByProductId(productId);
    }

    @Transactional
    public List<CatalogCardEffectResponse> replace(long productId, List<EffectFields> fields) {
        products.require(productId);
        effects.replaceForProduct(productId, fields);
        log.info("Replaced card effects of product {} with {} entries", productId, fields.size());
        return effects.findByProductId(productId);
    }
}
