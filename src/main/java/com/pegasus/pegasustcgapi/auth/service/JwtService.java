package com.pegasus.pegasustcgapi.auth.service;

import com.pegasus.pegasustcgapi.auth.model.AuthUser;
import com.pegasus.pegasustcgapi.auth.model.RoleCode;
import com.pegasus.pegasustcgapi.auth.security.AuthClaims;
import com.pegasus.pegasustcgapi.config.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints access tokens. Verification is the resource server's job, so this class
 * only ever writes — see {@code SecurityConfig#jwtDecoder} for the read side.
 *
 * <p>Roles are copied into the token, which is what makes authorisation possible
 * without a database round trip. The price is staleness: a role change only
 * takes effect on the holder's next refresh.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessToken issue(AuthUser user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwtIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.id()))
                .id(UUID.randomUUID().toString())
                .claim(AuthClaims.EMAIL, user.email())
                .claim(AuthClaims.USERNAME, user.username())
                .claim(AuthClaims.ROLES, user.roles().stream().map(RoleCode::name).toList())
                .claim(AuthClaims.TOKEN_TYPE, AuthClaims.ACCESS_TOKEN_TYPE)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new AccessToken(value, properties.accessTokenTtl().toSeconds());
    }

    public record AccessToken(String value, long expiresInSeconds) {
    }
}
