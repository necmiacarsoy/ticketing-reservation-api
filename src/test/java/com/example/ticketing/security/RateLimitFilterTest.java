package com.example.ticketing.security;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path, String ip)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void authEndpointsHaveTheirOwnStricterBudget() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimiter(Clock.systemUTC()), true, 2, 100);
        assertEquals(200, call(filter, "POST", "/api/auth/login", "10.0.0.1").getStatus());
        assertEquals(200, call(filter, "POST", "/api/auth/login", "10.0.0.1").getStatus());

        MockHttpServletResponse blocked = call(filter, "POST", "/api/auth/login", "10.0.0.1");
        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));
        assertTrue(blocked.getContentAsString().contains("RATE_LIMITED"));

        // other endpoints and other clients are unaffected
        assertEquals(200, call(filter, "GET", "/api/events/public", "10.0.0.1").getStatus());
        assertEquals(200, call(filter, "POST", "/api/auth/login", "10.0.0.2").getStatus());
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimiter(Clock.systemUTC()), false, 1, 1);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, call(filter, "POST", "/api/auth/login", "10.0.0.1").getStatus());
        }
    }

    @Test
    void nonApiPathsAreNotThrottled() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimiter(Clock.systemUTC()), true, 1, 1);
        for (int i = 0; i < 5; i++) {
            assertEquals(200, call(filter, "GET", "/actuator/health", "10.0.0.1").getStatus());
        }
    }
}
