package com.example.ticketing.exception;

public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static ApiException notFound(String what, Object id) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " " + id + " was not found");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException invalidState(String message) {
        return new ApiException(ErrorCode.INVALID_STATE, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHORIZED, message);
    }
}
