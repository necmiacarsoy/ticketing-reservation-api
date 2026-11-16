package com.example.ticketing.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, RateLimit rateLimit, Idempotency idempotency, Seed seed) {

    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {
    }

    public record RateLimit(boolean enabled, int authPerMinute, int apiPerMinute) {
    }

    public record Idempotency(Duration ttl, Duration inProgressTimeout) {
    }

    public record Seed(boolean enabled) {
    }
}
