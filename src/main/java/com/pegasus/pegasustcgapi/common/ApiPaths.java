package com.pegasus.pegasustcgapi.common;

/** Base paths, kept in one place so the version prefix is not spread across controllers. */
public final class ApiPaths {

    public static final String API_V1 = "/api/v1";
    public static final String AUTH = API_V1 + "/auth";
    public static final String USERS = API_V1 + "/users";

    private ApiPaths() {
    }
}
