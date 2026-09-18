package com.pegasus.pegasustcgapi.dto;

import com.pegasus.pegasustcgapi.model.ListingImage;

/**
 * @param imageKey what to send back to keep this photo when editing the listing
 * @param url      signed and short-lived
 */
public record ListingPhotoResponse(String imageKey, String url, boolean primary) {

    public static ListingPhotoResponse of(ListingImage image, String url) {
        return new ListingPhotoResponse(image.imageKey(), url, image.primary());
    }
}
