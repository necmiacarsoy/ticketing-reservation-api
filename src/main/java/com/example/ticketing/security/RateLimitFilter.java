package com.example.ticketing.security;

import java.io.IOException;

import com.example.ticketing.exception.ErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final boolean enabled;
    private final int authLimit;
    private final int apiLimit;

    public RateLimitFilter(RateLimiter limiter, boolean enabled, int authLimit, int apiLimit) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.authLimit = authLimit;
        this.apiLimit = apiLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!enabled || !path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        boolean authEndpoint = path.startsWith("/api/auth/");
        int limit = authEndpoint ? authLimit : apiLimit;
        String key = request.getRemoteAddr() + (authEndpoint ? "|auth" : "|api");
        RateLimiter.Decision decision = limiter.tryAcquire(key, limit);

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            JsonErrorWriter.write(response, ErrorCode.RATE_LIMITED, "Too many requests, slow down", path);
            return;
        }
        chain.doFilter(request, response);
    }
}
