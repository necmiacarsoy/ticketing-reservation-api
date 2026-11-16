package com.example.ticketing.security;

import com.example.ticketing.support.IntegrationTestBase;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.rate-limit.auth-per-minute=3",
        "spring.datasource.url=jdbc:h2:mem:ticketing-ratelimit;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
class RateLimitIntegrationTest extends IntegrationTestBase {

    @Test
    void authEndpointsAreThrottledPerClientAddress() throws Exception {
        String body = "{\"email\":\"nobody@test.example\",\"password\":\"wrong-password\"}";
        for (int i = 0; i < 3; i++) {
            postJson("/api/auth/login", null, body).andExpect(status().isUnauthorized());
        }
        postJson("/api/auth/login", null, body)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
