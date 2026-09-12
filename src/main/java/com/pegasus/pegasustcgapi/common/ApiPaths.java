package com.pegasus.pegasustcgapi.common;

/** Base paths, kept in one place so the version prefix is not spread across controllers. */
public final class ApiPaths {

    public static final String API_V1 = "/api/v1";
    public static final String AUTH = API_V1 + "/auth";
    public static final String USERS = API_V1 + "/users";
    public static final String ADDRESSES = API_V1 + "/addresses";
    /** The signed-in seller's own settings; the public profile stays at /u/{username}. */
    public static final String SELLERS_ME = API_V1 + "/sellers/me";
    public static final String ADMIN = API_V1 + "/admin";
    public static final String GAMES = API_V1 + "/games";
    public static final String CATEGORIES = API_V1 + "/categories";
    public static final String CARD_SETS = API_V1 + "/card-sets";
    public static final String CATALOG = API_V1 + "/catalog";
    /** Presigned upload tickets; the files themselves never pass through this API. */
    public static final String UPLOADS = API_V1 + "/uploads";

    private ApiPaths() {
    }
}
