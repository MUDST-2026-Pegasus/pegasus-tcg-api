package com.pegasus.pegasustcgapi.dto;

/** Only the profile fields visitors need on a storefront. */
public record PublicProfileResponse(String username, String displayName, String avatarUrl, String phone) {
}
