package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.ShippingOption;
import com.pegasus.pegasustcgapi.repository.ShippingOptionRepository;
import com.pegasus.pegasustcgapi.repository.ShippingOptionRepository.OptionFields;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery choices a seller offers and prices themselves [RQ-12].
 *
 * <p>Also answers the question checkout asks: what does this seller charge to
 * post this order.
 */
@Service
public class ShippingOptionService {

    private final ShippingOptionRepository options;
    private final SellerOnboardingService sellers;

    public ShippingOptionService(ShippingOptionRepository options, SellerOnboardingService sellers) {
        this.options = options;
        this.sellers = sellers;
    }

    public List<ShippingOption> listMine(long userId) {
        return options.findBySellerProfileId(sellers.requireProfile(userId).id(), false);
    }

    /** What a buyer is shown; deactivated options are gone from here. */
    public List<ShippingOption> listActive(long sellerProfileId) {
        return options.findBySellerProfileId(sellerProfileId, true);
    }

    @Transactional
    public ShippingOption create(long userId, OptionFields fields) {
        long sellerProfileId = sellers.requireVerifiedSeller(userId).id();
        long id = options.insert(sellerProfileId, fields);
        return options.findByIdAndSeller(id, sellerProfileId).orElseThrow(
                () -> new NotFoundException(ErrorCode.SHIPPING_OPTION_NOT_FOUND));
    }

    @Transactional
    public ShippingOption update(long userId, long optionId, OptionFields fields) {
        long sellerProfileId = sellers.requireProfile(userId).id();
        if (!options.update(optionId, sellerProfileId, fields)) {
            throw new NotFoundException(ErrorCode.SHIPPING_OPTION_NOT_FOUND);
        }
        return options.findByIdAndSeller(optionId, sellerProfileId).orElseThrow(
                () -> new NotFoundException(ErrorCode.SHIPPING_OPTION_NOT_FOUND));
    }

    /** Deactivates rather than deletes, so past orders keep resolving their option. */
    @Transactional
    public void deactivate(long userId, long optionId) {
        long sellerProfileId = sellers.requireProfile(userId).id();
        if (!options.deactivate(optionId, sellerProfileId)) {
            throw new NotFoundException(ErrorCode.SHIPPING_OPTION_NOT_FOUND);
        }
    }

    /** Resolves and validates one choice once, so its id and fee always belong to the same option. */
    public ShippingOption resolveForCheckout(long sellerProfileId, Long optionId) {
        if (optionId != null) {
            return options.findByIdAndSeller(optionId, sellerProfileId)
                    .filter(ShippingOption::active)
                    .orElseThrow(() -> new BadRequestException(ErrorCode.SHIPPING_OPTION_INVALID,
                            "Shipping option " + optionId + " is not active for seller " + sellerProfileId,
                            java.util.Map.of("sellerProfileId", sellerProfileId, "shippingOptionId", optionId)));
        }
        return listActive(sellerProfileId).stream().findFirst()
                .orElseThrow(() -> new BadRequestException(ErrorCode.SELLER_SHIPPING_UNAVAILABLE,
                        ErrorCode.SELLER_SHIPPING_UNAVAILABLE.defaultMessage(),
                        java.util.Map.of("sellerProfileId", sellerProfileId)));
    }

    /** Uses the same model calculation as quotes; inactive options cannot be priced for checkout. */
    public BigDecimal feeFor(long sellerProfileId, Long optionId, int itemCount, BigDecimal itemsSubtotal) {
        return resolveForCheckout(sellerProfileId, optionId).feeFor(itemCount, itemsSubtotal);
    }
}