package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.model.SellerStatus;
import com.pegasus.pegasustcgapi.model.SellerVerification;
import com.pegasus.pegasustcgapi.model.VerificationStatus;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.repository.SellerVerificationRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerOnboardingServiceTest {

    private static final long ADMIN = 9L;
    private static final long USER = 2L;
    private static final long PROFILE = 11L;
    private static final long REQUEST = 1L;
    private static final String ACCOUNT = "1234567890";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(FIXED);

    @Mock
    private SellerProfileRepository profiles;

    @Mock
    private SellerVerificationRepository verifications;

    @Mock
    private UserRepository users;

    @Mock
    private PayoutAccountRepository payoutAccounts;

    @Mock
    private RoleService roles;

    @Mock
    private StorageService storage;

    private SellerOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new SellerOnboardingService(profiles, verifications, users, payoutAccounts, roles, storage, FIXED);
    }

    private static SellerVerification request(VerificationStatus status) {
        return new SellerVerification(REQUEST, PROFILE, "Ploy", "Seller", "KBANK", "Kasikorn", ACCOUNT,
                "verifications/2026/09/book.jpg", status, NOW.minusDays(27), ADMIN, null, null);
    }

    @Nested
    class Approving {

        @Test
        @DisplayName("an account approved for another seller since the request was filed is a clear 409, and nothing moves")
        void accountTakenSinceSubmissionIsRefused() {
            given(verifications.findById(REQUEST)).willReturn(Optional.of(request(VerificationStatus.UNDER_REVIEW)));
            given(verifications.bankAccountUsedByAnother(ACCOUNT, PROFILE)).willReturn(true);

            assertThatThrownBy(() -> service.approve(REQUEST, ADMIN))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("reject it")
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.BANK_ACCOUNT_ALREADY_USED);

            verify(verifications, never()).transition(anyLong(), any(), any(), any(), any(), any());
            verify(profiles, never()).updateStatus(anyLong(), any(), any(), any());
            verifyNoInteractions(roles, payoutAccounts);
        }

        @Test
        @DisplayName("an account nobody else holds is approved: profile VERIFIED, SELLER granted, payout account registered")
        void freeAccountIsApproved() {
            given(verifications.findById(REQUEST)).willReturn(Optional.of(request(VerificationStatus.UNDER_REVIEW)));
            given(verifications.bankAccountUsedByAnother(ACCOUNT, PROFILE)).willReturn(false);
            given(verifications.transition(REQUEST, VerificationStatus.UNDER_REVIEW, VerificationStatus.APPROVED,
                    ADMIN, NOW, null)).willReturn(true);
            given(profiles.findById(PROFILE)).willReturn(Optional.of(
                    new SellerProfile(PROFILE, USER, SellerStatus.VERIFIED, NOW, null, (short) 2, false, true, NOW)));

            service.approve(REQUEST, ADMIN);

            verify(profiles).updateStatus(PROFILE, SellerStatus.VERIFIED, NOW, null);
            verify(roles).grant(USER, Set.of(RoleCode.SELLER), ADMIN);
            verify(payoutAccounts).upsert(eq(PROFILE), eq(REQUEST), eq("KBANK"), eq("Kasikorn"),
                    eq("Ploy Seller"), eq(ACCOUNT));
        }

        @Test
        @DisplayName("a request already decided still says so, rather than talking about the account")
        void decidedRequestKeepsItsOwnError() {
            given(verifications.findById(REQUEST)).willReturn(Optional.of(request(VerificationStatus.APPROVED)));

            assertThatThrownBy(() -> service.approve(REQUEST, ADMIN))
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.VERIFICATION_ALREADY_DECIDED);

            verify(verifications, never()).bankAccountUsedByAnother(any(), anyLong());
        }
    }
}
