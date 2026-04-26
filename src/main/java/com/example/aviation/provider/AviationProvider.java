package com.example.aviation.provider;

import com.example.aviation.exception.AirportNotFoundException;
import com.example.aviation.exception.UpstreamServiceException;
import com.example.aviation.model.AirportInfo;

/**
 * Strategy interface for aviation data providers.
 * Each concrete provider implements this interface, encapsulating its own API call logic.
 */
public interface AviationProvider {

    /**
     * Fetches airport information by ICAO code.
     *
     * @param icaoCode four-letter ICAO airport identifier
     * @return airport information domain model
     * @throws AirportNotFoundException when no airport is found for the given code
     * @throws UpstreamServiceException when the upstream service is unavailable or returns an error
     */
    AirportInfo fetchAirportByIcao(String icaoCode);
}
