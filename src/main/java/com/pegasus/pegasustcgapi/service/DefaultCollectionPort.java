package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.port.CollectionPort;
import com.pegasus.pegasustcgapi.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
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
    public void grant(UUID userId, List<UUID> orderItemUnitIds) {
        // Stub for UUID-based callers
    }

    @Override
    public void grant(long buyerUserId, long sellerOrderId) {
        orderRepository.grantPurchasedUnitsToCollection(buyerUserId, sellerOrderId);
    }
}
