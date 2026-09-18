package com.pegasus.pegasustcgapi.port;

import com.pegasus.pegasustcgapi.model.SellerProfile;

/**
 * What the listing module needs to know about sellers, and nothing more. The
 * seller module owns the answer; listings only ask.
 */
public interface SellerPort {

    /**
     * For reading: a seller who has since been suspended still sees their own
     * listings and stock.
     *
     * @throws com.pegasus.pegasustcgapi.exception.NotFoundException when the
     *         account never started selling
     */
    SellerProfile requireProfile(long userId);

    /**
     * The gate before anything is created, published or changed [RQ-1].
     *
     * @throws com.pegasus.pegasustcgapi.exception.ForbiddenException unless VERIFIED
     */
    SellerProfile requireVerifiedSeller(long userId);
}
