package com.example.aviation.exception;

/**
 * Base exception for all Aviation API Wrapper exceptions.
 */
public class AviationBaseException extends RuntimeException {

    public AviationBaseException(String message) {
        super(message);
    }

    public AviationBaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
