package com.pegasus.pegasustcgapi.controller.home;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.common.ApiResult;
import com.pegasus.pegasustcgapi.dto.home.HomeBannerResponse;
import com.pegasus.pegasustcgapi.dto.home.RecommendedShopResponse;
import com.pegasus.pegasustcgapi.service.home.HomeBannerService;
import com.pegasus.pegasustcgapi.service.home.RecommendedShopService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What only the home page needs. The rest of it — games, categories, trending
 * and the market — comes from the catalogue and listing endpoints it shares with
 * every other page.
 */
@RestController
@RequestMapping(ApiPaths.HOME)
public class HomeController {

    private final HomeBannerService banners;
    private final RecommendedShopService recommendedShops;

    public HomeController(HomeBannerService banners, RecommendedShopService recommendedShops) {
        this.banners = banners;
        this.recommendedShops = recommendedShops;
    }

    /** The carousel slides live right now, in display order. */
    @GetMapping("/banners")
    public ApiResult<List<HomeBannerResponse>> banners() {
        return ApiResult.success(banners.live());
    }

    @GetMapping("/recommended-shops")
    public ApiResult<List<RecommendedShopResponse>> recommendedShops() {
        return ApiResult.success(recommendedShops.recommended());
    }
}
