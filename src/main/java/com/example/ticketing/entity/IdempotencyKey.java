package com.example.ticketing.entity;

import java.time.Instant;

import com.example.ticketing.enums.IdempotencyStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_user_key", columnNames = {"user_id", "key_value"}),
        indexes = @Index(name = "idx_idempotency_expires", columnList = "expires_at"))
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "key_value", nullable = false, length = 255)
    private String keyValue;

    @Column(nullable = false, length = 255)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_hash", length = 64)
    private String responseHash;

    @Column(name = "resource_id")
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {
        // for JPA
    }

    public IdempotencyKey(Long userId, String keyValue, String endpoint, String requestHash,
                          Instant now, Instant expiresAt) {
        this.userId = userId;
        this.keyValue = keyValue;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean matches(String otherEndpoint, String otherRequestHash) {
        return endpoint.equals(otherEndpoint) && requestHash.equals(otherRequestHash);
    }

    public void complete(Long resourceId, String responseHash, Instant newExpiresAt) {
        this.status = IdempotencyStatus.COMPLETED;
        this.resourceId = resourceId;
        this.responseHash = responseHash;
        this.expiresAt = newExpiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseHash() {
        return responseHash;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
