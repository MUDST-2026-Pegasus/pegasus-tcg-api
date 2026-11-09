package com.pegasus.pegasustcgapi.service.auth;

/**
 * Verifies a Google ID token and extracts the authenticated identity claims.
 */
public interface GoogleTokenVerifier {

    GoogleIdentity verify(String credential);

    record GoogleIdentity(
            String subject,
            String email,
            boolean emailVerified,
            String name,
            String hostedDomain) {

        public GoogleIdentity(String subject, String email, String name, String hostedDomain) {
            this(subject, email, true, name, hostedDomain);
        }

        public boolean hasHostedDomain() {
            return hostedDomain != null && !hostedDomain.isBlank();
        }

        @Override
        public String toString() {
            return "GoogleIdentity[subject=REDACTED, email=REDACTED, emailVerified=" + emailVerified
                    + ", name=REDACTED, hostedDomain=" + hostedDomain + "]";
        }
    }
}
