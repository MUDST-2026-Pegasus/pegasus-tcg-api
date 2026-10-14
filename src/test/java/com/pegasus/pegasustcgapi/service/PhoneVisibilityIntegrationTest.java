package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.dto.UpdateProfileRequest;
import com.pegasus.pegasustcgapi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PhoneVisibilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired UserProfileService profiles;
    @Autowired ListingBrowseService browse;

    @Test
    void phoneRemainsPrivateUntilSharedAndCanBeMadePrivateAgain() {
        var owner = data.buyer();
        profiles.updateProfile(owner.userId(), new UpdateProfileRequest(null, null, "0812345678", null));
        assertThat(browse.profile(owner.username()).phone()).isNull();

        var shared = profiles.updateProfile(owner.userId(), new UpdateProfileRequest(null, null, null, null, true));
        assertThat(shared.phonePublic()).isTrue();
        assertThat(browse.profile(owner.username()).phone()).isEqualTo("0812345678");

        var hidden = profiles.updateProfile(owner.userId(), new UpdateProfileRequest(null, null, null, null, false));
        assertThat(hidden.phonePublic()).isFalse();
        assertThat(hidden.phone()).isEqualTo("0812345678");
        assertThat(browse.profile(owner.username()).phone()).isNull();
    }
}
