package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.auth.LoginRequest;
import com.pegasus.pegasustcgapi.dto.auth.TokenResponse;
import com.pegasus.pegasustcgapi.model.auth.IssuedToken;
import com.pegasus.pegasustcgapi.model.finance.payout.PayoutAccount;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class SensitiveToStringTest {

    @Test
    void authenticationAndBankAccountObjectsDoNotExposeSecretsInToString() {
        assertRedacted(new LoginRequest("person@example.com", "plain-password"),
                "person@example.com", "plain-password");
        assertRedacted(TokenResponse.bearer("access-jwt", 900, "refresh-secret", 3600),
                "access-jwt", "refresh-secret");
        assertRedacted(new IssuedToken(3, "opaque-refresh-token", OffsetDateTime.now()),
                "opaque-refresh-token");
        assertRedacted(new PayoutAccount(1, 2, "BK", "Bank", "Jane Doe",
                "1234567890", 4, OffsetDateTime.now(), OffsetDateTime.now()),
                "Jane Doe", "1234567890");
    }

    private static void assertRedacted(Object value, String... secrets) {
        String rendered = value.toString();
        for (String secret : secrets) {
            assertThat(rendered).doesNotContain(secret);
        }
        assertThat(rendered).contains("REDACTED");
    }
}
