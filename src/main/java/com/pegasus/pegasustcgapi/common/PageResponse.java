package com.pegasus.pegasustcgapi.common;

import java.util.List;

/**
 * Envelope for paged endpoints. Nothing in the auth module returns a page yet;
 * it lives here so catalogue and listing endpoints share one shape from the start.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }
}
