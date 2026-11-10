package com.pegasus.pegasustcgapi.model.audit;

/**
 * Every action the audit log knows about, in one place.
 *
 * <p>Each action is tied to the one kind of target it can be about, so a call
 * site cannot file an approval under the wrong table. The name is what is
 * stored, so renaming a constant orphans the rows already written under it.
 */
public enum AuditAction {

    SELLER_VERIFICATION_SUBMITTED(AuditTargetType.SELLER_VERIFICATION),
    SELLER_VERIFICATION_REVIEW_STARTED(AuditTargetType.SELLER_VERIFICATION),
    SELLER_VERIFICATION_APPROVED(AuditTargetType.SELLER_VERIFICATION),
    SELLER_VERIFICATION_REJECTED(AuditTargetType.SELLER_VERIFICATION),

    CATALOG_PRODUCT_CREATED(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_PRODUCT_UPDATED(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_PRODUCT_DELETED(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_PRODUCT_EFFECTS_REPLACED(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_IMAGE_ADDED(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_IMAGE_PRIMARY_SET(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_IMAGE_DELETED(AuditTargetType.CATALOG_PRODUCT),
    CATALOG_VARIANT_CREATED(AuditTargetType.CATALOG_VARIANT),
    CATALOG_VARIANT_UPDATED(AuditTargetType.CATALOG_VARIANT),

    GAME_CREATED(AuditTargetType.GAME),
    GAME_UPDATED(AuditTargetType.GAME),
    GAME_ATTRIBUTE_CREATED(AuditTargetType.GAME),
    GAME_ATTRIBUTE_UPDATED(AuditTargetType.GAME),
    GAME_ATTRIBUTE_DELETED(AuditTargetType.GAME),
    CATALOG_CATEGORY_CREATED(AuditTargetType.CATALOG_CATEGORY),
    CATALOG_CATEGORY_UPDATED(AuditTargetType.CATALOG_CATEGORY),
    CARD_SET_CREATED(AuditTargetType.CARD_SET),
    CARD_SET_UPDATED(AuditTargetType.CARD_SET),
    CARD_SET_DELETED(AuditTargetType.CARD_SET),

    SETTING_UPDATED(AuditTargetType.SETTING),

    USER_ROLE_GRANTED(AuditTargetType.USER),
    USER_ROLE_REVOKED(AuditTargetType.USER),
    USER_STATUS_CHANGED(AuditTargetType.USER),

    MARKET_PRICING_RUN(AuditTargetType.MARKET_PRICING),

    COMMISSION_RULE_CREATED(AuditTargetType.COMMISSION_RULE),
    COMMISSION_RULE_UPDATED(AuditTargetType.COMMISSION_RULE),
    COMMISSION_RULE_DEACTIVATED(AuditTargetType.COMMISSION_RULE),
    COMMISSION_RULE_DELETED(AuditTargetType.COMMISSION_RULE),

    PAYMENT_APPROVED(AuditTargetType.PAYMENT),
    PAYMENT_REJECTED(AuditTargetType.PAYMENT),

    PAYOUT_APPROVED(AuditTargetType.PAYOUT),
    PAYOUT_PROCESSING(AuditTargetType.PAYOUT),
    PAYOUT_PAID(AuditTargetType.PAYOUT),
    PAYOUT_REJECTED(AuditTargetType.PAYOUT),
    PAYOUT_FAILED(AuditTargetType.PAYOUT),

    REFUND_PROCESSING(AuditTargetType.REFUND),
    REFUND_PROCESSED(AuditTargetType.REFUND),
    REFUND_FAILED(AuditTargetType.REFUND),

    /** One row per payment-timeout run that cancelled anything, not one per order. */
    UNPAID_ORDERS_EXPIRED(AuditTargetType.JOB),
    /** One row per auto-release run that completed anything, not one per order. */
    ORDERS_AUTO_RELEASED(AuditTargetType.JOB);

    private final AuditTargetType targetType;

    AuditAction(AuditTargetType targetType) {
        this.targetType = targetType;
    }

    public AuditTargetType targetType() {
        return targetType;
    }
}
