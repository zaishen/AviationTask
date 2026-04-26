package com.example.aviation.exception.handler;

import com.example.aviation.exception.*;
import com.example.aviation.model.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import net.jqwik.api.*;

import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Property 5: Unified error response format without leaking internal details.
 *
 * For any exception type, the error JSON returned by GlobalExceptionHandler
 * must contain all five fields (timestamp, status, error, message, path) non-null,
 * and the message must not leak stack traces or internal class names.
 *
 * Validates: Requirements 6.1, 6.2, 12.2
 */
class ErrorResponseFormatPropertyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest mockRequest(String path) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }

    /**
     * **Validates: Requirements 6.1, 6.2, 12.2**
     *
     * For any randomly generated exception from the known set,
     * the ErrorResponse must have all 5 fields non-null.
     */
    @Property(tries = 100)
    void allErrorResponsesHaveRequiredFields(@ForAll("randomExceptions") ExceptionScenario scenario) {
        HttpServletRequest request = mockRequest(scenario.path());
        ResponseEntity<ErrorResponse> response = invokeHandler(handler, scenario.exception(), request);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTimestamp()).isNotNull();
        assertThat(body.getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(body.getError()).isNotNull().isNotBlank();
        assertThat(body.getMessage()).isNotNull().isNotBlank();
        assertThat(body.getPath()).isNotNull().isEqualTo(scenario.path());

        // HTTP status in body must match the ResponseEntity status
        assertThat(body.getStatus()).isEqualTo(response.getStatusCode().value());
    }

    /**
     * **Validates: Requirements 6.1, 6.2, 12.2**
     *
     * For generic Exception (500 handler), the message must NOT contain
     * stack trace patterns or internal class names.
     */
    @Property(tries = 100)
    void genericExceptionDoesNotLeakInternalDetails(
            @ForAll("dangerousMessages") String dangerousMessage,
            @ForAll("requestPaths") String path) {
        Exception ex = new RuntimeException(dangerousMessage);
        HttpServletRequest request = mockRequest(path);

        ResponseEntity<ErrorResponse> response = handler.handleGeneric(ex, request);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        String message = body.getMessage();

        // Must not contain stack trace indicators
        assertThat(message).doesNotContain("at ");
        assertThat(message).doesNotContain(".java:");
        assertThat(message).doesNotContain("java.lang.");
        assertThat(message).doesNotContain("Exception");
        assertThat(message).doesNotContain("java.io.");
        assertThat(message).doesNotContain("java.util.");
        assertThat(message).doesNotContain("org.springframework.");
    }

    // --- Providers ---

    @Provide
    Arbitrary<ExceptionScenario> randomExceptions() {
        Arbitrary<String> paths = requestPaths();

        return Arbitraries.oneOf(
                // InvalidIcaoCodeException -> 400
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6).flatMap(code ->
                        paths.map(p -> new ExceptionScenario(new InvalidIcaoCodeException(code), p))
                ),
                // AirportNotFoundException -> 404
                Arbitraries.strings().alpha().ofLength(4).flatMap(code ->
                        paths.map(p -> new ExceptionScenario(new AirportNotFoundException(code), p))
                ),
                // UpstreamServiceException -> 502
                Arbitraries.integers().between(500, 599).flatMap(statusCode ->
                        Arbitraries.strings().ascii().ofMinLength(1).ofMaxLength(50)
                                .filter(s -> !s.isBlank())
                                .flatMap(msg -> paths.map(p ->
                                        new ExceptionScenario(new UpstreamServiceException(statusCode, msg), p)))
                ),
                // CircuitBreakerOpenException -> 503
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20).flatMap(provider ->
                        paths.map(p -> new ExceptionScenario(new CircuitBreakerOpenException(provider), p))
                ),
                // UpstreamRateLimitException -> 429
                Arbitraries.longs().between(1, 300).flatMap(retryAfter ->
                        paths.map(p -> new ExceptionScenario(new UpstreamRateLimitException(retryAfter), p))
                ),
                // Generic RuntimeException -> 500
                Arbitraries.strings().ascii().ofMinLength(1).ofMaxLength(100)
                        .filter(s -> !s.isBlank())
                        .flatMap(msg -> paths.map(p ->
                                new ExceptionScenario(new RuntimeException(msg), p)))
        );
    }

    @Provide
    Arbitrary<String> dangerousMessages() {
        return Arbitraries.oneOf(
                // Messages that might contain stack traces
                Arbitraries.just("at com.example.SomeClass.method(SomeClass.java:42)"),
                Arbitraries.just("java.lang.NullPointerException: something was null"),
                Arbitraries.just("org.springframework.beans.factory.BeanCreationException"),
                Arbitraries.just("Caused by: java.io.IOException: connection reset"),
                // Random messages
                Arbitraries.strings().ascii().ofMinLength(1).ofMaxLength(200).filter(s -> !s.isBlank())
        );
    }

    @Provide
    Arbitrary<String> requestPaths() {
        return Arbitraries.oneOf(
                Arbitraries.just("/api/v1/airports/KJFK"),
                Arbitraries.just("/api/v1/airports/RCTP"),
                Arbitraries.just("/api/v1/airports/EGLL"),
                Arbitraries.strings().alpha().ofLength(4).map(code -> "/api/v1/airports/" + code)
        );
    }

    // --- Helper ---

    private ResponseEntity<ErrorResponse> invokeHandler(
            GlobalExceptionHandler handler, Exception ex, HttpServletRequest request) {
        if (ex instanceof InvalidIcaoCodeException e) {
            return handler.handleInvalidIcao(e, request);
        } else if (ex instanceof AirportNotFoundException e) {
            return handler.handleNotFound(e, request);
        } else if (ex instanceof UpstreamServiceException e) {
            return handler.handleUpstreamService(e, request);
        } else if (ex instanceof CircuitBreakerOpenException e) {
            return handler.handleCircuitBreakerOpen(e, request);
        } else if (ex instanceof UpstreamRateLimitException e) {
            return handler.handleUpstreamRateLimit(e, request);
        } else {
            return handler.handleGeneric(ex, request);
        }
    }

    // --- Record for test scenarios ---

    record ExceptionScenario(Exception exception, String path) {}
}
