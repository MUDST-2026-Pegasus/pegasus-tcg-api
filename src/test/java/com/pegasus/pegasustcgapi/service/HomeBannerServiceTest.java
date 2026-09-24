package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.pegasus.pegasustcgapi.dto.HomeBannerResponse;
import com.pegasus.pegasustcgapi.model.HomeBanner;
import com.pegasus.pegasustcgapi.repository.HomeBannerRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HomeBannerServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private HomeBannerRepository banners;

    @Mock
    private StorageService storage;

    private HomeBannerService service;

    @BeforeEach
    void setUp() {
        service = new HomeBannerService(banners, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static HomeBanner banner(int id, String imageKey) {
        return new HomeBanner(id, "NEW FROM PEGASUS", "FRESH CARDS.\nREADY TO PLAY.", "This week's releases.",
                imageKey, "Cards on a table", "CAMPAIGN", "Shop New Releases", "/search?sort=newest",
                null, null, (short) id, true, null, null);
    }

    @Test
    @DisplayName("live slides are asked for as of now, and leave with a signed picture")
    void liveSlidesAreSigned() {
        given(banners.findLive(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)))
                .willReturn(List.of(banner(1, "banners/campaign.jpg")));
        given(storage.readUrl("banners/campaign.jpg")).willReturn("http://localhost:9000/signed");

        List<HomeBannerResponse> slides = service.live();

        assertThat(slides).singleElement().satisfies(slide -> {
            assertThat(slide.imageUrl()).isEqualTo("http://localhost:9000/signed");
            assertThat(slide.title()).isEqualTo("FRESH CARDS.\nREADY TO PLAY.");
            assertThat(slide.primaryHref()).isEqualTo("/search?sort=newest");
            assertThat(slide.secondaryLabel()).isNull();
        });
    }

    @Test
    @DisplayName("no live slides is an empty carousel, not an error")
    void nothingLive() {
        given(banners.findLive(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))).willReturn(List.of());

        assertThat(service.live()).isEmpty();
    }
}
