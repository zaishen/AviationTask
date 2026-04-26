package com.example.aviation.exception;

import lombok.Getter;

/**
 * Thrown when no airport data is found for the given ICAO code in the upstream API.
 */
@Getter
public class AirportNotFoundException extends AviationBaseException {

    private final String icaoCode;

    public AirportNotFoundException(String icaoCode) {
        super("Airport not found for ICAO code: " + icaoCode);
        this.icaoCode = icaoCode;
    }

    public AirportNotFoundException(String icaoCode, Throwable cause) {
        super("Airport not found for ICAO code: " + icaoCode, cause);
        this.icaoCode = icaoCode;
    }
}
