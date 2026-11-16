package com.example.ticketing.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.example.ticketing.config.AppProperties;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;
import com.example.ticketing.exception.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_TOKEN_TYPE = "token_type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder refreshDecoder;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        AppProperties.Jwt jwt = properties.jwt();
        byte[] secret = jwt.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.issuer = jwt.issuer();
        this.accessTtl = jwt.accessTtl();
        this.refreshTtl = jwt.refreshTtl();
        this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = buildDecoder(key, TYPE_ACCESS);
        this.refreshDecoder = buildDecoder(key, TYPE_REFRESH);
    }

    public String issueAccessToken(User user) {
        return issue(user, TYPE_ACCESS, accessTtl);
    }

    public String issueRefreshToken(User user) {
        return issue(user, TYPE_REFRESH, refreshTtl);
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    public JwtDecoder accessDecoder() {
        return accessDecoder;
    }

    public Jwt parseRefreshToken(String token) {
        try {
            return refreshDecoder.decode(token);
        } catch (JwtException e) {
            throw ApiException.unauthorized("Invalid or expired refresh token");
        }
    }

    private String issue(User user, String type, Duration ttl) {
        Instant now = clock.instant();
        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TOKEN_TYPE, type)
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLES, roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private JwtDecoder buildDecoder(SecretKey key, String expectedType) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> typeValidator = token ->
                expectedType.equals(token.getClaimAsString(CLAIM_TOKEN_TYPE))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "Unexpected token type", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), typeValidator));
        return decoder;
    }
}
