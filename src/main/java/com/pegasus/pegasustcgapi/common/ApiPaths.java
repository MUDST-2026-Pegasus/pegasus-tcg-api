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
    /** The signed-in user's own collection; the public view lives at /users/{username}/collection. */
    public static final String COLLECTION = API_V1 + "/collection";
    /** The market: every listing on sale, whoever sells it. A seller's own are under /sellers/me/listings. */
    public static final String LISTINGS = API_V1 + "/listings";
    /** A profile, which is also the storefront — there is no separate shop [RQ-2]. */
    public static final String PROFILES = API_V1 + "/u";
    /** Presigned upload tickets; the files themselves never pass through this API. */
    public static final String UPLOADS = API_V1 + "/uploads";
    /** The active cart for guest or signed-in users. */
    public static final String CART = API_V1 + "/cart";
    public static final String CART_ITEMS = CART + "/items";
    /** Checkout process to convert cart into sales and seller orders. */
    public static final String CHECKOUT = API_V1 + "/checkout";
    /** The signed-in buyer's orders. */
    public static final String ORDERS = API_V1 + "/orders";
    /** The signed-in seller's own orders. */
    public static final String SELLERS_ME_ORDERS = SELLERS_ME + "/orders";

    private ApiPaths() {
    }
}
