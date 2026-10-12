package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.port.CollectionPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import org.springframework.stereotype.Service;

/**
 * Default implementation of CollectionPort granting purchased cards to buyer collections.
 */
@Service
public class DefaultCollectionPort implements CollectionPort {

    private final OrderRepository orderRepository;

    public DefaultCollectionPort(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public void grant(long buyerUserId, long sellerOrderId) {
        orderRepository.grantPurchasedUnitsToCollection(buyerUserId, sellerOrderId);
    }
}
