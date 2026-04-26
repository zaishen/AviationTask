package com.example.aviation.exception.handler;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.aviation.exception.AirportNotFoundException;
import com.example.aviation.exception.CircuitBreakerOpenException;
import com.example.aviation.exception.InvalidIcaoCodeException;
import com.example.aviation.exception.UpstreamRateLimitException;
import com.example.aviation.exception.UpstreamServiceException;
import com.example.aviation.model.ErrorResponse;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Global exception handler using Template Method style for unified error responses.
 * Each @ExceptionHandler delegates to buildErrorResponse for consistent JSON structure.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Template Method: builds a unified ErrorResponse with consistent structure.
     */
    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(InvalidIcaoCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIcao(
            InvalidIcaoCodeException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(AirportNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            AirportNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamService(
            UpstreamServiceException ex, HttpServletRequest request) {
        log.error("Upstream service error: statusCode={}, message={}",
                ex.getUpstreamStatusCode(), ex.getUpstreamMessage(), ex);
        return buildErrorResponse(HttpStatus.BAD_GATEWAY,
                "Upstream service is temporarily unavailable", request);
    }

    @ExceptionHandler(CircuitBreakerOpenException.class)
    public ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(
            CircuitBreakerOpenException ex, HttpServletRequest request) {
        log.error("Circuit breaker open: provider={}", ex.getProviderName(), ex);
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable, please try again later", request);
    }

    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamRateLimit(
            UpstreamRateLimitException ex, HttpServletRequest request) {
        ResponseEntity<ErrorResponse> response = buildErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
        String retryAfter = ex.getRetryAfterSeconds() != null
                ? String.valueOf(ex.getRetryAfterSeconds()) : "1";
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfter)
                .body(response.getBody());
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
            RequestNotPermitted ex, HttpServletRequest request) {
        ResponseEntity<ErrorResponse> response = buildErrorResponse(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, please try again later", request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(response.getBody());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error", request);
    }
}
