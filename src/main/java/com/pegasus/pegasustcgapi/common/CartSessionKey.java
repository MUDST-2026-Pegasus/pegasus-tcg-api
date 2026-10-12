package com.pegasus.pegasustcgapi.common;

import com.pegasus.pegasustcgapi.exception.BadRequestException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The {@code X-Cart-Session} header, checked before it becomes a row.
 *
 * <p>The guest cart endpoints are open to anyone, and the key arrives in a header
 * the client controls. Accepting whatever it sends lets a caller name any key it
 * likes — including one it saw in somebody else's request — so only the shape this
 * API issues is accepted: a random v4 UUID, which is also comfortably inside
 * {@code cart.session_key varchar(128)}.
 */
public final class CartSessionKey {

    private static final Pattern UUID_FORM = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private CartSessionKey() {
    }

    /** A fresh key to hand back to a guest. */
    public static String issue() {
        return UUID.randomUUID().toString();
    }

    /**
     * @return the trimmed key, or null when the header was absent or blank
     * @throws BadRequestException the header is present but is not a key this API issues
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!UUID_FORM.matcher(trimmed).matches()) {
            throw new BadRequestException(ErrorCode.CART_SESSION_INVALID);
        }
        return trimmed;
    }
}
