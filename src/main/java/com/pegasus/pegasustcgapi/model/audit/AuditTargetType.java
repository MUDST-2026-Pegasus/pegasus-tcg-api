package com.pegasus.pegasustcgapi.model.audit;

/**
 * What an audit row is about. Together with {@code target_id} it answers "show me
 * the history of this thing", so a child that has no page of its own (a product
 * image, a game attribute) is filed under its parent and named in the metadata.
 */
public enum AuditTargetType {
    SELLER_VERIFICATION,
    CATALOG_PRODUCT,
    CATALOG_VARIANT,
    GAME,
    CATALOG_CATEGORY,
    CARD_SET,
    SEARCH_ALIAS,
    SETTING,
    USER,
    MARKET_PRICING,
    COMMISSION_RULE,
    PAYMENT,
    PAYOUT,
    REFUND,
    /** A scheduled job's run; the target id is the job's name. */
    JOB
}
