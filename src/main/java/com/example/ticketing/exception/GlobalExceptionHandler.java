package com.example.ticketing.exception;

import java.time.Instant;
import java.util.List;

import com.example.ticketing.dto.ApiError;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return build(ex.getCode(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed", request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "Malformed or unreadable request body", request, List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR,
                "Missing required header '" + ex.getHeaderName() + "'", request, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                       HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR,
                "Missing required parameter '" + ex.getParameterName() + "'", request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR,
                "Invalid value for parameter '" + ex.getName() + "'", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, "Resource not found", request, List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                         HttpServletRequest request) {
        return build(ErrorCode.CONFLICT, "The resource was modified concurrently; reload and retry", request,
                List.of());
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleLockFailure(PessimisticLockingFailureException ex,
                                                      HttpServletRequest request) {
        LOG.warn("Lock acquisition failed: {}", ex.getMessage());
        return build(ErrorCode.BUSY, "The system is busy, please retry shortly", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        LOG.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "Unexpected server error", request, List.of());
    }

    private static ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest request,
                                                  List<ApiError.FieldViolation> errors) {
        ApiError body = new ApiError(Instant.now(), code.status(), code.name(), message,
                request.getRequestURI(), errors);
        return ResponseEntity.status(code.status()).body(body);
    }
}
