package com.pegasus.pegasustcgapi.service.home;

import com.pegasus.pegasustcgapi.dto.home.RecommendedShopResponse;
import com.pegasus.pegasustcgapi.repository.home.RecommendedShopRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RecommendedShopService {

    private final RecommendedShopRepository shops;
    private final StorageService storage;

    public RecommendedShopService(RecommendedShopRepository shops, StorageService storage) {
        this.shops = shops;
        this.storage = storage;
    }

    public List<RecommendedShopResponse> recommended() {
        var ranked = shops.topShops();
        if (ranked.isEmpty()) return List.of();

        Map<Long, List<RecommendedShopRepository.Preview>> previews = shops.previews(
                ranked.stream().map(RecommendedShopRepository.Shop::sellerId).toList()).stream()
                .collect(Collectors.groupingBy(RecommendedShopRepository.Preview::sellerId));

        return ranked.stream().map(shop -> new RecommendedShopResponse(
                shop.username(), shop.displayName(), signed(shop.avatarKey()), signed(shop.bannerKey()),
                shop.ratingAvg(), shop.ratingCount(), shop.activeListingCount(),
                previews.getOrDefault(shop.sellerId(), List.of()).stream()
                        .map(preview -> new RecommendedShopResponse.Preview(
                                preview.listingId(), signed(preview.imageKey())))
                        .toList())).toList();
    }

    private String signed(String key) {
        return key == null ? null : storage.presignDownload(key);
    }
}
