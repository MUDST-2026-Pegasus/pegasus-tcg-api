package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * A freshly minted opaque token. {@code value} is the only time the plain text
 * exists — it is handed to the caller and then only its hash remains.
 */
public record IssuedToken(long id, String value, OffsetDateTime expiresAt) {
}
