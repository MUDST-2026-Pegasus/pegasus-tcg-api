package com.pegasus.pegasustcgapi.service.collection;

import com.pegasus.pegasustcgapi.port.collection.CollectionPort;
import com.pegasus.pegasustcgapi.repository.order.OrderTransitionRepository;
import org.springframework.stereotype.Service;

/**
 * Default implementation of CollectionPort granting purchased cards to buyer collections.
 */
@Service
public class DefaultCollectionPort implements CollectionPort {

    private final OrderTransitionRepository orderTransitionRepository;

    public DefaultCollectionPort(OrderTransitionRepository orderTransitionRepository) {
        this.orderTransitionRepository = orderTransitionRepository;
    }

    @Override
    public void grant(long buyerUserId, long sellerOrderId) {
        orderTransitionRepository.grantPurchasedUnitsToCollection(buyerUserId, sellerOrderId);
    }
}
