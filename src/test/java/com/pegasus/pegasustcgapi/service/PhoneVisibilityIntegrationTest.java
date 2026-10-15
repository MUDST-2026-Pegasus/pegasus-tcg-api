package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.dto.UpdateProfileRequest;
import com.pegasus.pegasustcgapi.support.ApiClient;
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

    @Test
    void anonymousStorefrontVisitorSeesPhoneOnlyDuringExplicitOptIn() {
        var owner = data.buyer();
        profiles.updateProfile(owner.userId(), new UpdateProfileRequest(null, null, "0812345678", null));
        var client = new ApiClient(port);
        String path = ApiPaths.PROFILES + "/" + owner.username();

        var privateResponse = client.get(path, null);
        assertThat(privateResponse.status()).isEqualTo(200);
        assertThat(privateResponse.data().path("phone").asString("")).isEmpty();

        profiles.updateProfile(owner.userId(), new UpdateProfileRequest(null, null, null, null, true));
        var publicResponse = client.get(path, null);
        assertThat(publicResponse.status()).isEqualTo(200);
        assertThat(publicResponse.data().path("phone").asString()).isEqualTo("0812345678");

        profiles.updateProfile(owner.userId(), new UpdateProfileRequest(null, null, null, null, false));
        var hiddenAgain = client.get(path, null);
        assertThat(hiddenAgain.status()).isEqualTo(200);
        assertThat(hiddenAgain.data().path("phone").asString("")).isEmpty();
    }
}
