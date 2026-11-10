package com.pegasus.pegasustcgapi.common;

/** Shows an admin which mailbox an account uses without exposing the address. */
public final class EmailMask {

    private EmailMask() {
    }

    /** Keeps the first character and the domain: {@code kanna@gmail.com} becomes {@code k***@gmail.com}. */
    public static String mask(String email) {
        return email == null ? null : email.replaceFirst("(?<=.).*(?=@)", "***");
    }
}
