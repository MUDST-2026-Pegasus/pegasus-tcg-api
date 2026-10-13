package com.pegasus.pegasustcgapi.dto;

/** Public profile fields and the number of visible copies of this product. */
public record ProductCollectorResponse(
        String username,
        String displayName,
        String avatarUrl,
        long quantity) {
}
