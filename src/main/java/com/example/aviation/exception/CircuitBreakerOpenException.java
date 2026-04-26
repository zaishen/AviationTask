package com.example.aviation.exception;

import lombok.Getter;

/**
 * Thrown when the circuit breaker is in the open state,
 * meaning the upstream service is temporarily unavailable.
 */
@Getter
public class CircuitBreakerOpenException extends AviationBaseException {

    private final String providerName;

    public CircuitBreakerOpenException(String providerName) {
        super("Circuit breaker is open for provider: " + providerName);
        this.providerName = providerName;
    }

    public CircuitBreakerOpenException(String providerName, Throwable cause) {
        super("Circuit breaker is open for provider: " + providerName, cause);
        this.providerName = providerName;
    }
}
