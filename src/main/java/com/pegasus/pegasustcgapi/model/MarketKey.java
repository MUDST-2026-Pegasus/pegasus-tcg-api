package com.pegasus.pegasustcgapi.model;

/**
 * One market: a printing in a condition. Median prices, and which listings an
 * AUTO_MEDIAN price follows, are both decided at this level [RQ-5].
 */
public record MarketKey(long catalogVariantId, CardCondition condition) {
}
