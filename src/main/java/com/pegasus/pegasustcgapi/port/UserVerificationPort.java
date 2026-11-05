package com.pegasus.pegasustcgapi.port;

/**
 * Port for inspecting user account verification standing [TCG-616].
 */
public interface UserVerificationPort {

    /**
     * Checks whether the user's email address is verified.
     *
     * @param userId the database user ID
     * @return true if email is verified, false otherwise
     */
    boolean isEmailVerified(long userId);
}
