package com.pegasus.pegasustcgapi.support;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A deterministic stand-in for Argon2. Real hashing costs ~16 MiB and tens of
 * milliseconds per call, which a unit test has no use for; this keeps the one
 * property the services rely on — a hash matches its own plain text and nothing
 * else — while staying instant and predictable in assertions.
 *
 * <p>Wrap it in a Mockito spy when a test also wants to verify the calls.
 */
public class FakePasswordEncoder implements PasswordEncoder {

    public static String encoded(String rawPassword) {
        return "hashed:" + rawPassword;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return encoded(String.valueOf(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return encode(rawPassword).equals(encodedPassword);
    }
}
