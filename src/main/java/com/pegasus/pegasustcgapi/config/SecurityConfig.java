package com.pegasus.pegasustcgapi.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.SecurityErrorHandler;
import com.pegasus.pegasustcgapi.common.ApiPaths;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless bearer-token security: no session, no CSRF token, no login form.
 * Access tokens are HS256 JWTs this service both mints and verifies with the
 * same shared key, which is why the encoder and decoder live side by side here.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** HS256 needs at least as much key material as it produces output. */
    private static final int MIN_SECRET_BYTES = 32;

    /** Reachable without a token: the ways in, and the ways back in. */
    private static final String[] PUBLIC_POST_ENDPOINTS = {
        ApiPaths.AUTH + "/register",
        ApiPaths.AUTH + "/login",
        ApiPaths.AUTH + "/refresh",
        ApiPaths.AUTH + "/logout",
        ApiPaths.AUTH + "/password/forgot",
        ApiPaths.AUTH + "/password/reset",
    };

    /**
     * Readable without an account. Someone deciding whether to sign up is exactly
     * who needs to see the catalogue first, and none of it is anybody's data.
     */
    private static final String[] PUBLIC_GET_ENDPOINTS = {
        ApiPaths.GAMES + "/**",
        ApiPaths.CATEGORIES + "/**",
        ApiPaths.CARD_SETS + "/**",
        ApiPaths.CATALOG + "/**",
        // A profile's shown cards; the owner's own list stays behind sign-in.
        ApiPaths.USERS + "/*/collection",
        // The market and a seller's storefront. A seller's own dashboard is under /sellers/me.
        ApiPaths.LISTINGS + "/**",
        ApiPaths.PROFILES + "/*/listings",
    };

    /** Public Swagger UI and OpenAPI documentation resources. */
    private static final String[] SWAGGER_WHITELIST = {
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/swagger-resources/**",
        "/webjars/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            SecurityErrorHandler securityErrorHandler) throws Exception {

        return http
                // Nothing is authenticated by a cookie, so there is no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers(SWAGGER_WHITELIST).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .build();
    }

    /**
     * Argon2id with Spring Security's current defaults (m=16MiB, t=2, p=1). The
     * BouncyCastle provider on the classpath is what backs it.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecretKey jwtSigningKey(AuthProperties properties) {
        byte[] material = decodeSecret(properties.jwtSecret());
        if (material.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "pegasus.auth.jwt-secret must be at least " + MIN_SECRET_BYTES
                            + " bytes; generate one with: openssl rand -base64 48");
        }
        return new SecretKeySpec(material, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(properties.jwtIssuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** Turns the {@code roles} claim into {@code ROLE_*} authorities for {@code hasRole}. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(AuthClaims.ROLES);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // Tokens travel in the Authorization header, never in a cookie.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(ApiPaths.API_V1 + "/**", config);
        return source;
    }

    /** Accepts a base64 key as generated by {@code openssl rand -base64 48}, or plain text. */
    private static byte[] decodeSecret(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
