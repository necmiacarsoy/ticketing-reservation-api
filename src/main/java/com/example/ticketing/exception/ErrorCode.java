package com.example.ticketing.exception;

public enum ErrorCode {
    VALIDATION_ERROR(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    CONFLICT(409),
    SOLD_OUT(409),
    INVALID_STATE(409),
    IDEMPOTENCY_IN_PROGRESS(409),
    IDEMPOTENCY_KEY_REUSED(422),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    BUSY(503);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
