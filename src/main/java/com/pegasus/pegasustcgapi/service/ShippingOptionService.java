package com.pegasus.pegasustcgapi.service;

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

    /**
     * Shipping for one sub-order.
     *
     * @param optionId null falls back to the seller's first active option
     * @param itemCount cards in this seller's part of the basket, not the whole basket
     * @throws NotFoundException the option is not this seller's, or they have none configured
     */
    public BigDecimal feeFor(long sellerProfileId, Long optionId, int itemCount, BigDecimal itemsSubtotal) {
        ShippingOption option = optionId == null
                ? firstActive(sellerProfileId)
                : options.findByIdAndSeller(optionId, sellerProfileId).orElseThrow(
                        () -> new NotFoundException(ErrorCode.SHIPPING_OPTION_NOT_FOUND));

        return option.feeFor(itemCount, itemsSubtotal);
    }

    private ShippingOption firstActive(long sellerProfileId) {
        return options.findBySellerProfileId(sellerProfileId, true).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.SHIPPING_OPTION_NOT_FOUND,
                        "This seller has no active shipping option"));
    }
}
