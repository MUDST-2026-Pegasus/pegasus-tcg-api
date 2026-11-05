package com.pegasus.pegasustcgapi.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.pegasus.pegasustcgapi.model.auth.AuthUser;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultUserVerificationPort Unit Tests")
class DefaultUserVerificationPortTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DefaultUserVerificationPort port;

    @Test
    @DisplayName("returns true when user exists and email is not blank")
    void isEmailVerified_WhenUserHasEmail_ReturnsTrue() {
        AuthUser user = new AuthUser(
                1L, "user@example.com", "username", "Display", "hash",
                null, "0812345678", null, UserStatus.ACTIVE, (short) 0,
                null, null, OffsetDateTime.now(), Set.of());
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        boolean verified = port.isEmailVerified(1L);

        assertThat(verified).isTrue();
    }

    @Test
    @DisplayName("returns false when user does not exist")
    void isEmailVerified_WhenUserNotFound_ReturnsFalse() {
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        boolean verified = port.isEmailVerified(99L);

        assertThat(verified).isFalse();
    }

    @Test
    @DisplayName("returns false when user exists but email is blank")
    void isEmailVerified_WhenEmailIsBlank_ReturnsFalse() {
        AuthUser user = new AuthUser(
                2L, "  ", "username2", "Display", "hash",
                null, null, null, UserStatus.ACTIVE, (short) 0,
                null, null, OffsetDateTime.now(), Set.of());
        given(userRepository.findById(2L)).willReturn(Optional.of(user));

        boolean verified = port.isEmailVerified(2L);

        assertThat(verified).isFalse();
    }
}
