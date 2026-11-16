package com.example.ticketing.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.example.ticketing.config.AppProperties;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;
import com.example.ticketing.exception.ApiException;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    private static AppProperties props(String secret) {
        return new AppProperties(
                new AppProperties.Jwt(secret, "test-issuer", Duration.ofMinutes(15), Duration.ofDays(7)),
                null, null, null);
    }

    private static User user() {
        return new User("a@b.example", "hash", Set.of(Role.ORGANIZER, Role.CUSTOMER));
    }

    @Test
    void accessTokenRoundTripCarriesRoles() {
        JwtService service = new JwtService(props(SECRET), Clock.systemUTC());
        String token = service.issueAccessToken(user());

        Jwt jwt = service.accessDecoder().decode(token);

        assertEquals("test-issuer", jwt.getClaimAsString("iss"));
        assertTrue(jwt.getClaimAsStringList(JwtService.CLAIM_ROLES).containsAll(
                Set.of("ORGANIZER", "CUSTOMER")));
        assertEquals("access", jwt.getClaimAsString(JwtService.CLAIM_TOKEN_TYPE));
    }

    @Test
    void refreshTokenIsRejectedAsAccessTokenAndViceVersa() {
        JwtService service = new JwtService(props(SECRET), Clock.systemUTC());
        String refresh = service.issueRefreshToken(user());
        String access = service.issueAccessToken(user());

        assertThrows(JwtException.class, () -> service.accessDecoder().decode(refresh));
        assertThrows(ApiException.class, () -> service.parseRefreshToken(access));
        assertEquals("refresh", service.parseRefreshToken(refresh).getClaimAsString(JwtService.CLAIM_TOKEN_TYPE));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtService issuer = new JwtService(props(SECRET), Clock.systemUTC());
        JwtService other = new JwtService(props("another-secret-another-secret-123456"), Clock.systemUTC());
        String forged = issuer.issueAccessToken(user());

        assertThrows(JwtException.class, () -> other.accessDecoder().decode(forged));
    }

    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        JwtService service = new JwtService(props(SECRET), past);
        String expired = service.issueAccessToken(user());

        assertThrows(JwtException.class, () -> service.accessDecoder().decode(expired));
    }

    @Test
    void garbageTokenIsRejected() {
        JwtService service = new JwtService(props(SECRET), Clock.systemUTC());
        assertThrows(JwtException.class, () -> service.accessDecoder().decode("not-a-jwt"));
    }

    @Test
    void shortSecretFailsFast() {
        assertThrows(IllegalStateException.class, () -> new JwtService(props("too-short"), Clock.systemUTC()));
    }
}
