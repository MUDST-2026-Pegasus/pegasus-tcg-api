package com.pegasus.pegasustcgapi.support;

import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.UserStatus;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Builds {@link AuthUser} fixtures. The record has fourteen components and a test
 * usually cares about two of them, so everything has a sensible default here and
 * a test names only what it is actually about.
 */
public final class AuthUserBuilder {

    public static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-01-01T09:00:00Z");

    private long id = 42L;
    private String email = "ploy@example.com";
    private String username = "ploy";
    private String displayName = "Ploy";
    private String passwordHash = FakePasswordEncoder.encoded("correct-horse");
    private String bio;
    private String phone = "+66812345678";
    private String avatarUrl;
    private UserStatus status = UserStatus.ACTIVE;
    private short failedLoginAttempts;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime lockedUntil;
    private Set<RoleCode> roles = Set.of();

    private AuthUserBuilder() {
    }

    /** An active BUYER whose password is {@code correct-horse}. */
    public static AuthUserBuilder anActiveUser() {
        return new AuthUserBuilder().withRoles(RoleCode.BUYER);
    }

    public AuthUserBuilder withId(long value) {
        this.id = value;
        return this;
    }

    public AuthUserBuilder withEmail(String value) {
        this.email = value;
        return this;
    }

    public AuthUserBuilder withUsername(String value) {
        this.username = value;
        return this;
    }

    public AuthUserBuilder withPassword(String rawPassword) {
        this.passwordHash = FakePasswordEncoder.encoded(rawPassword);
        return this;
    }

    public AuthUserBuilder withStatus(UserStatus value) {
        this.status = value;
        return this;
    }

    public AuthUserBuilder withFailedLoginAttempts(int value) {
        this.failedLoginAttempts = (short) value;
        return this;
    }

    public AuthUserBuilder withLockedUntil(OffsetDateTime value) {
        this.lockedUntil = value;
        return this;
    }

    public AuthUserBuilder withDisplayName(String value) {
        this.displayName = value;
        return this;
    }

    public AuthUserBuilder withBio(String value) {
        this.bio = value;
        return this;
    }

    public AuthUserBuilder withPhone(String value) {
        this.phone = value;
        return this;
    }

    public AuthUserBuilder withAvatarUrl(String value) {
        this.avatarUrl = value;
        return this;
    }

    public AuthUserBuilder withRoles(RoleCode... values) {
        this.roles = Set.of(values);
        return this;
    }

    public AuthUser build() {
        return new AuthUser(id, email, username, displayName, passwordHash, bio, phone, avatarUrl,
                status, failedLoginAttempts, lastLoginAt, lockedUntil, CREATED_AT, roles);
    }
}
