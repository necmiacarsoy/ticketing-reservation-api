package com.example.ticketing.controller;

import java.util.List;
import java.util.UUID;

import com.example.ticketing.entity.AuditLog;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.AuditAction;
import com.example.ticketing.enums.Role;
import com.example.ticketing.support.IntegrationTestBase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestBase {

    private static String registerJson(String email, String password, String role) {
        String roleField = role == null ? "" : ",\"role\":\"" + role + "\"";
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + roleField + "}";
    }

    private static String newEmail() {
        return "auth-" + UUID.randomUUID() + "@test.example";
    }

    @Test
    void registerCreatesCustomerByDefaultAndNeverExposesPasswordHash() throws Exception {
        String email = newEmail();

        postJson("/api/auth/register", null, registerJson(email, PASSWORD, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        User saved = userRepository.findByEmail(email).orElseThrow();
        assertTrue(passwordEncoder.matches(PASSWORD, saved.getPasswordHash()), "password must be stored as BCrypt");
        assertTrue(saved.getPasswordHash().startsWith("$2"), "BCrypt hash expected");
    }

    @Test
    void registerNormalizesEmailAndRejectsDuplicates() throws Exception {
        String email = newEmail();
        postJson("/api/auth/register", null, registerJson(email, PASSWORD, "ORGANIZER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("ORGANIZER"));

        postJson("/api/auth/register", null, registerJson(email.toUpperCase(), PASSWORD, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void adminCannotBeSelfRegistered() throws Exception {
        postJson("/api/auth/register", null, registerJson(newEmail(), PASSWORD, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void registerValidatesInput() throws Exception {
        postJson("/api/auth/register", null, registerJson("not-an-email", "short", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void loginIssuesTokensThatWorkAgainstProtectedEndpoints() throws Exception {
        User organizer = createUser("login", Role.ORGANIZER);
        String body = "{\"email\":\"" + organizer.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}";

        var result = postJson("/api/auth/login", null, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
        String access = jsonString(result, "$.accessToken");
        assertNotNull(jsonString(result, "$.refreshToken"));

        get("/api/events", access).andExpect(status().isOk());
        assertNotNull(userRepository.findById(organizer.getId()).orElseThrow().getLastLoginAt());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericError() throws Exception {
        User user = createUser("badlogin", Role.CUSTOMER);

        postJson("/api/auth/login", null,
                "{\"email\":\"" + user.getEmail() + "\",\"password\":\"wrong-password\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid e-mail or password"));
        postJson("/api/auth/login", null,
                "{\"email\":\"nobody-" + UUID.randomUUID() + "@test.example\",\"password\":\"whatever123\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid e-mail or password"));
    }

    @Test
    void refreshExchangesRefreshTokenForNewPair() throws Exception {
        User user = createUser("refresh", Role.CUSTOMER);
        var login = postJson("/api/auth/login", null,
                "{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}");
        String refresh = jsonString(login, "$.refreshToken");

        var refreshed = postJson("/api/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}")
                .andExpect(status().isOk());
        String newAccess = jsonString(refreshed, "$.accessToken");

        postJson("/api/events/1/reservations", newAccess, "{\"seats\":1}")
                .andExpect(status().isBadRequest()); // authenticated (missing Idempotency-Key), not 401/403
    }

    @Test
    void accessTokenCannotBeUsedToRefreshAndRefreshTokenCannotCallTheApi() throws Exception {
        User user = createUser("tokentype", Role.ORGANIZER);
        String access = jwtService.issueAccessToken(user);
        String refresh = jwtService.issueRefreshToken(user);

        postJson("/api/auth/refresh", null, "{\"refreshToken\":\"" + access + "\"}")
                .andExpect(status().isUnauthorized());
        get("/api/events", refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        User user = createUser("tamper", Role.ORGANIZER);
        String token = jwtService.issueAccessToken(user);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        get("/api/events", tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void authActionsAreAudited() throws Exception {
        String email = newEmail();
        postJson("/api/auth/register", null, registerJson(email, PASSWORD, null)).andExpect(status().isCreated());
        postJson("/api/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"nope-nope-nope\"}")
                .andExpect(status().isUnauthorized());
        postJson("/api/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk());

        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        List<AuditLog> logs = auditLogRepository.findByActorIdOrderByIdAsc(userId);
        List<AuditAction> actions = logs.stream().map(AuditLog::getAction).toList();

        assertEquals(List.of(AuditAction.USER_REGISTERED, AuditAction.LOGIN_FAILED, AuditAction.LOGIN_SUCCESS),
                actions);
        assertNotNull(logs.get(0).getIp(), "client IP must be captured");
    }
}
