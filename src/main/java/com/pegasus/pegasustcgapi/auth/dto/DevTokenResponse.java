package com.pegasus.pegasustcgapi.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the endpoints that would normally only send an e-mail. The field stays
 * empty unless {@code pegasus.auth.dev-expose-tokens} is on, so the response is
 * the same whether or not the address belongs to an account.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DevTokenResponse(String token) {

    public static DevTokenResponse of(String token) {
        return new DevTokenResponse(token);
    }

    public static DevTokenResponse hidden() {
        return new DevTokenResponse(null);
    }
}
