package com.pegasus.pegasustcgapi.storage;

import com.pegasus.pegasustcgapi.model.RoleCode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a file is for. One purpose decides three things at once: where the object
 * lands, who may put one there, and what may be put.
 *
 * <p>The role here is the coarse gate — "sellers upload listing photos" — not the
 * fine one. Whether this particular seller owns that particular listing is the
 * feature's own check, made when it attaches the key to a row.
 */
public enum UploadPurpose {

    /** Official card art, added with the catalogue entry [RQ-3]. */
    CATALOG_IMAGE("catalog", RoleCode.ADMIN, Allowed.IMAGES, 5 * Allowed.MB),
    /** Photos of the actual card, taken by the seller [RQ-8]. */
    LISTING_IMAGE("listings", RoleCode.SELLER, Allowed.IMAGES, 5 * Allowed.MB),
    /** Bank transfer slip a buyer uploads for an order [CR-4]. */
    PAYMENT_SLIP("payments", null, Allowed.DOCUMENTS, 5 * Allowed.MB),
    /** Transfer slip an admin files against a payout. */
    PAYOUT_SLIP("payouts", RoleCode.ADMIN, Allowed.DOCUMENTS, 5 * Allowed.MB),
    /** Buyer's photos of what arrived, attached to a return request. */
    RETURN_EVIDENCE("returns", null, Allowed.IMAGES, 10 * Allowed.MB),
    /** Proof of postage filed by the seller. */
    SHIPMENT_PROOF("shipments", RoleCode.SELLER, Allowed.IMAGES, 5 * Allowed.MB);

    /** Enum constants are built before the enum's own statics, so these live here. */
    private static final class Allowed {

        static final long MB = 1024L * 1024L;

        static final Map<String, String> IMAGES = Map.of(
                "image/jpeg", "jpg",
                "image/png", "png",
                "image/webp", "webp");

        static final Map<String, String> DOCUMENTS = Map.of(
                "image/jpeg", "jpg",
                "image/png", "png",
                "image/webp", "webp",
                "application/pdf", "pdf");

        private Allowed() {
        }
    }

    private final String prefix;
    private final RoleCode requiredRole;
    private final Map<String, String> extensionByContentType;
    private final long maxBytes;

    UploadPurpose(String prefix, RoleCode requiredRole,
            Map<String, String> extensionByContentType, long maxBytes) {

        this.prefix = prefix;
        this.requiredRole = requiredRole;
        this.extensionByContentType = extensionByContentType;
        this.maxBytes = maxBytes;
    }

    /** First segment of the object key, so one glance at a key says what it is. */
    public String prefix() {
        return prefix;
    }

    /** {@code null} means any signed-in account may upload this. */
    public RoleCode requiredRole() {
        return requiredRole;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public Set<String> allowedContentTypes() {
        return extensionByContentType.keySet();
    }

    public boolean allows(String contentType) {
        return extensionFor(contentType) != null;
    }

    /** @return the extension to give the object, or {@code null} when the type is not allowed. */
    public String extensionFor(String contentType) {
        return contentType == null ? null : extensionByContentType.get(normalise(contentType));
    }

    /** Browsers send {@code image/jpeg; charset=…} often enough to be worth stripping. */
    private static String normalise(String contentType) {
        int separator = contentType.indexOf(';');
        String type = separator < 0 ? contentType : contentType.substring(0, separator);
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
