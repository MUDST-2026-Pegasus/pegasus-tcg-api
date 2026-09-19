package com.pegasus.pegasustcgapi.port;

import java.util.List;
import java.util.UUID;

/**
 * Collection management port stub for granting purchased cards to buyers.
 */
public interface CollectionPort {

    void grant(UUID userId, List<UUID> orderItemUnitIds);
}
