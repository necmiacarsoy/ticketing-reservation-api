package com.example.ticketing.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.example.ticketing.config.AppProperties;
import com.example.ticketing.entity.Event;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;
import com.example.ticketing.support.IntegrationTestBase;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityRbacIntegrationTest extends IntegrationTestBase {

    @Test
    void protectedEndpointsRejectAnonymousCallers() throws Exception {
        get("/api/events", null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        postJson("/api/events", null, eventJson("x", 5)).andExpect(status().isUnauthorized());
        postEmpty("/api/reservations/1/confirm", null).andExpect(status().isUnauthorized());
        reserve(null, 1L, 1, "k").andExpect(status().isUnauthorized());
    }

    @Test
    void publicDiscoveryAndHealthAreOpen() throws Exception {
        get("/api/events/public", null).andExpect(status().isOk());
        get("/actuator/health", null).andExpect(status().isOk());
        get("/v3/api-docs", null).andExpect(status().isOk());
    }

    @Test
    void malformedOrTamperedTokenIsRejected() throws Exception {
        get("/api/events", "not-a-jwt").andExpect(status().isUnauthorized());
        String valid = tokenFor(createUser("org", Role.ORGANIZER));
        get("/api/events", valid.substring(0, valid.length() - 3) + "abc").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() throws Exception {
        User user = createUser("org", Role.ORGANIZER);
        String refresh = jwtService.issueRefreshToken(user);
        get("/api/events", refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotManageEvents() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        Event event = createPublishedEvent(owner, 5);
        String customer = tokenFor(createUser("cust", Role.CUSTOMER));

        postJson("/api/events", customer, eventJson("x", 5)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        putJson("/api/events/" + event.getId(), customer, eventJson("x", 5)).andExpect(status().isForbidden());
        postEmpty("/api/events/" + event.getId() + "/publish", customer).andExpect(status().isForbidden());
        get("/api/events", customer).andExpect(status().isForbidden());
    }

    @Test
    void organizerCannotReserveButCanManageOwnEvents() throws Exception {
        User organizer = createUser("org", Role.ORGANIZER);
        Event event = createPublishedEvent(organizer, 5);
        String token = tokenFor(organizer);

        reserve(token, event.getId(), 1, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        postJson("/api/events", token, eventJson("Mine", 5)).andExpect(status().isCreated());
        get("/api/events", token).andExpect(status().isOk());
    }

    @Test
    void organizerCannotTouchSomeoneElsesEvent() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        Event event = createPublishedEvent(owner, 5);
        String intruder = tokenFor(createUser("intruder", Role.ORGANIZER));

        putJson("/api/events/" + event.getId(), intruder, eventJson("x", 5)).andExpect(status().isForbidden());
        postEmpty("/api/events/" + event.getId() + "/publish", intruder).andExpect(status().isForbidden());
    }

    @Test
    void adminHasFullAccess() throws Exception {
        User owner = createUser("owner", Role.ORGANIZER);
        Event event = createPublishedEvent(owner, 5);
        String admin = tokenFor(createUser("admin", Role.ADMIN));

        putJson("/api/events/" + event.getId(), admin, eventJson("Renamed by admin", 5)).andExpect(status().isOk());
        get("/api/events", admin).andExpect(status().isOk());
        reserve(admin, event.getId(), 1, UUID.randomUUID().toString()).andExpect(status().isCreated());
        get("/actuator/metrics", admin).andExpect(status().isOk());
    }

    @Test
    void actuatorBeyondHealthRequiresAdmin() throws Exception {
        get("/actuator/metrics", null).andExpect(status().isUnauthorized());
        get("/actuator/metrics", tokenFor(createUser("cust", Role.CUSTOMER))).andExpect(status().isForbidden());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        User user = createUser("org", Role.ORGANIZER);
        AppProperties past = new AppProperties(new AppProperties.Jwt(
                "test-secret-test-secret-test-secret-test-secret", "ticketing-api",
                Duration.ofMinutes(15), Duration.ofDays(7)), null, null, null);
        // a token issued two hours ago with a 15 minute lifetime
        String expired = new JwtService(past, Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC))
                .issueAccessToken(user);
        get("/api/events", expired).andExpect(status().isUnauthorized());
    }
}
