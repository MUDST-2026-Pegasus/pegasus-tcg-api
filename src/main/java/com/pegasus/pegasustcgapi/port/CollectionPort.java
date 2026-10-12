package com.pegasus.pegasustcgapi.port;

/**
 * Collection management port stub for granting purchased cards to buyers.
 */
public interface CollectionPort {

    void grant(long buyerUserId, long sellerOrderId);
}
