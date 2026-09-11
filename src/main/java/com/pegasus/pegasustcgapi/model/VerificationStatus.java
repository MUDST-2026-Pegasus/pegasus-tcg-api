package com.pegasus.pegasustcgapi.model;

/** Progress of one identity-document submission. */
public enum VerificationStatus {

    SUBMITTED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED;

    /** A decided submission cannot be reviewed again; the seller must send a new one. */
    public boolean isFinal() {
        return this == APPROVED || this == REJECTED;
    }
}
