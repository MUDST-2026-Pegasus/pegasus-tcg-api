package com.pegasus.pegasustcgapi.common;

/**
 * A page request made safe before it reaches SQL.
 *
 * <p>Both halves of that matter. A negative page or size goes straight into
 * {@code LIMIT}/{@code OFFSET}, which Postgres refuses with an error that surfaces
 * as a 500. And {@code page * size} in {@code int} overflows for a large page —
 * 21,474,837 × 100 wraps negative — so the offset is worked out in {@code long}
 * and capped, which simply returns an empty page.
 *
 * @param page   zero-based, never negative
 * @param size   between 1 and the cap
 * @param offset never negative
 */
public record Paging(int page, int size, int offset) {

    public static final int MAX_SIZE = 100;

    public static Paging of(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        long offset = (long) safePage * safeSize;
        return new Paging(safePage, safeSize, (int) Math.min(offset, Integer.MAX_VALUE));
    }
}
