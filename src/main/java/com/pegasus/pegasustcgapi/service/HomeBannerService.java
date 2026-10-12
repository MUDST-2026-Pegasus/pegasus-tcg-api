package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.HomeBannerResponse;
import com.pegasus.pegasustcgapi.repository.HomeBannerRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** The home page carousel: whatever is switched on and inside its dates right now. */
@Service
public class HomeBannerService {

    private final HomeBannerRepository banners;
    private final StorageService storage;
    private final Clock clock;

    public HomeBannerService(HomeBannerRepository banners, StorageService storage, Clock clock) {
        this.banners = banners;
        this.storage = storage;
        this.clock = clock;
    }

    public List<HomeBannerResponse> live() {
        return banners.findLive(OffsetDateTime.now(clock)).stream()
                .map(banner -> HomeBannerResponse.of(banner, storage.readUrl(banner.imageKey())))
                .toList();
    }
}
