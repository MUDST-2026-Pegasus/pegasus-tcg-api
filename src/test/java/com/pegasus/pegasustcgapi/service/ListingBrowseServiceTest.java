package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.ListingImageRepository;
import com.pegasus.pegasustcgapi.repository.ListingRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.repository.VariantMarketStatRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListingBrowseServiceTest {
    @Mock ListingRepository listings;
    @Mock ListingImageRepository images;
    @Mock VariantMarketStatRepository stats;
    @Mock UserRepository users;
    @Mock ListingCardRenderer cards;
    @InjectMocks ListingBrowseService browse;

    @Test
    void publicProfileIncludesNameAndSignedAvatarWithoutAnyListings() {
        given(users.findByUsername("cardvault")).willReturn(Optional.of(anActiveUser()
                .withUsername("cardvault").withDisplayName("CardVault BKK")
                .withAvatarUrl("avatars/cardvault.png").build()));
        given(cards.signed("avatars/cardvault.png"))
                .willReturn("https://cdn.example.com/cardvault.png?sig=abc");

        var profile = browse.profile("CARDVAULT");

        assertThat(profile.username()).isEqualTo("cardvault");
        assertThat(profile.displayName()).isEqualTo("CardVault BKK");
        assertThat(profile.avatarUrl()).isEqualTo("https://cdn.example.com/cardvault.png?sig=abc");
    }

    @Test
    void suspendedProfileIsNotPublic() {
        given(users.findByUsername("cardvault")).willReturn(Optional.of(anActiveUser()
                .withUsername("cardvault").withStatus(UserStatus.SUSPENDED).build()));

        assertThatThrownBy(() -> browse.profile("cardvault"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }
    @Test
    void phoneIsNotReturnedForPrivateProfiles() {
        given(users.findByUsername("private" )).willReturn(Optional.of(anActiveUser()
                .withUsername("private").withPhone("0812345678").withPhonePublic(false).build()));
        assertThat(browse.profile("private").phone()).isNull();
    }

    @Test
    void phoneIsReturnedOnlyAfterOwnerOptsIn() {
        var owner = anActiveUser().withUsername("public").withPhone("0812345678")
                .withPhonePublic(true).build();
        given(users.findByUsername("public")).willReturn(Optional.of(owner));
        assertThat(browse.profile("public").phone()).isEqualTo("0812345678");
        assertThat(owner.withRoles(java.util.Set.of()).phonePublic()).isTrue();
    }
}
