package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.HomeBanner;

/**
 * A home page slide, ready to draw.
 *
 * @param imageUrl a short-lived read URL for the slide picture
 * @param theme    CAMPAIGN, RELEASE or COLLECTOR
 * @param primaryHref   a path inside the web client, e.g. {@code /search?sort=newest}; null hides the button
 * @param secondaryHref same, for the outlined button
 */
public record HomeBannerResponse(
        int id,
        String eyebrow,
        String title,
        String description,
        String imageUrl,
        String imageAlt,
        String theme,
        String primaryLabel,
        String primaryHref,
        String secondaryLabel,
        String secondaryHref) {

    public static HomeBannerResponse of(HomeBanner banner, String imageUrl) {
        return new HomeBannerResponse(banner.id(), banner.eyebrow(), banner.title(), banner.description(),
                imageUrl, banner.imageAlt(), banner.theme(),
                banner.primaryLabel(), banner.primaryHref(),
                banner.secondaryLabel(), banner.secondaryHref());
    }
}
