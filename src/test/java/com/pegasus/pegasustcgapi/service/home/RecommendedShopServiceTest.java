package com.pegasus.pegasustcgapi.service.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pegasus.pegasustcgapi.repository.home.RecommendedShopRepository;
import com.pegasus.pegasustcgapi.repository.home.RecommendedShopRepository.Preview;
import com.pegasus.pegasustcgapi.repository.home.RecommendedShopRepository.Shop;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendedShopServiceTest {

    private final RecommendedShopRepository repository = mock(RecommendedShopRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final RecommendedShopService service = new RecommendedShopService(repository, storage);

    @Test
    void emptyRankingDoesNotFetchPreviewsOrSignAnything() {
        when(repository.topShops()).thenReturn(List.of());

        assertThat(service.recommended()).isEmpty();
        verifyNoInteractions(storage);
    }

    @Test
    void loadsPreviewsInOneBatchAndKeepsDatabaseRanking() {
        when(repository.topShops()).thenReturn(List.of(
                new Shop(9, "busy", "Busy Shop", "avatars/busy.png", "banners/busy.png", new BigDecimal("4.80"), 100, 12),
                new Shop(3, "new", "New Shop", null, null, BigDecimal.ZERO, 0, 1)));
        when(repository.previews(List.of(9L, 3L))).thenReturn(List.of(
                new Preview(9, 91, "photos/91.png"), new Preview(9, 90, null),
                new Preview(3, 31, "photos/31.png")));
        when(storage.presignDownload("avatars/busy.png")).thenReturn("signed-avatar");
        when(storage.presignDownload("banners/busy.png")).thenReturn("signed-banner");
        when(storage.presignDownload("photos/91.png")).thenReturn("signed-91");
        when(storage.presignDownload("photos/31.png")).thenReturn("signed-31");

        var result = service.recommended();

        assertThat(result).extracting(shop -> shop.username()).containsExactly("busy", "new");
        assertThat(result.getFirst().avatarUrl()).isEqualTo("signed-avatar");
        assertThat(result.getFirst().bannerUrl()).isEqualTo("signed-banner");
        assertThat(result.get(1).bannerUrl()).isNull();
        assertThat(result.getFirst().previews()).extracting(preview -> preview.listingId())
                .containsExactly(91L, 90L);
        assertThat(result.getFirst().previews().getFirst().imageUrl()).isEqualTo("signed-91");
        assertThat(result.get(1).ratingCount()).isZero();
        verify(repository).previews(List.of(9L, 3L));
    }
}
