package com.pegasus.pegasustcgapi.service.auction;

import com.pegasus.pegasustcgapi.model.shipping.ShippingOption;
import com.pegasus.pegasustcgapi.repository.shipping.ShippingOptionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The shipping fee shown for an auction: the seller's first active option, the same one
 * auction checkout charges when the buyer picks none. Falls back to the policy default
 * only when the seller has no active option.
 */
@Component
public class AuctionShippingFee {

    private final ShippingOptionRepository options;

    @Autowired
    public AuctionShippingFee(ShippingOptionRepository options) {
        this.options = options;
    }

    /** Always the policy default; for callers built without a repository. */
    public static AuctionShippingFee fixed() {
        return new AuctionShippingFee(null);
    }

    public BigDecimal quote(long sellerProfileId, BigDecimal price) {
        if (options == null) return AuctionPolicy.DEFAULT_SHIPPING;
        ShippingOption option = options.findDefaultActiveBySellerIds(List.of(sellerProfileId)).get(sellerProfileId);
        return option == null ? AuctionPolicy.DEFAULT_SHIPPING : option.feeFor(1, price);
    }
}
