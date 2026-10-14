package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.model.PayoutAccount;
import com.pegasus.pegasustcgapi.model.SellerProfile;
import com.pegasus.pegasustcgapi.repository.PayoutAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PayoutAccountServiceTest {
    @Mock PayoutAccountRepository accounts;
    @Mock SellerOnboardingService sellers;

    @Test void sellerSeesOnlyAccountLinkedToOwnVerifiedProfile() {
        var account = new PayoutAccount(4L, 3L, "TEST", "Test Bank", "Seller",
                "1234567890", 1L, null, null);
        when(sellers.requireProfile(7L)).thenReturn(new SellerProfile(3L, 7L,
                null, null, null, (short) 0, false, false, null));
        when(accounts.findBySellerProfileId(3L)).thenReturn(Optional.of(account));

        assertThat(new PayoutAccountService(accounts, sellers).mine(7L)).isEqualTo(account);
        verify(accounts).findBySellerProfileId(3L);
    }
}
