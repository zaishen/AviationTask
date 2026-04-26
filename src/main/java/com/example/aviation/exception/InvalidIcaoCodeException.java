package com.example.aviation.exception;

import lombok.Getter;

/**
 * Thrown when the provided ICAO code does not match the expected format
 * (exactly four alphabetic characters, case-insensitive).
 */
@Getter
public class InvalidIcaoCodeException extends AviationBaseException {

    private final String icaoCode;

    public InvalidIcaoCodeException(String icaoCode) {
        super("Invalid ICAO code: " + icaoCode);
        this.icaoCode = icaoCode;
    }

    public InvalidIcaoCodeException(String icaoCode, Throwable cause) {
        super("Invalid ICAO code: " + icaoCode, cause);
        this.icaoCode = icaoCode;
    }
}
