package com.example.aviation.service;

import com.example.aviation.model.AirportInfo;
import com.example.aviation.model.AirportResponse;
import com.example.aviation.provider.AviationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AirportService {

    private static final Logger log = LoggerFactory.getLogger(AirportService.class);

    private final AviationProvider aviationProvider;

    public AirportService(AviationProvider aviationProvider) {
        this.aviationProvider = aviationProvider;
    }

    @Cacheable(value = "airports", key = "#icaoCode.toUpperCase()")
    public AirportResponse queryAirport(String icaoCode) {
        String normalizedCode = icaoCode.toUpperCase();
        Instant requestTimestamp = Instant.now();
        long startTime = System.nanoTime();

        log.info("Airport query started: ICAO_Code={}, requestTimestamp={}", normalizedCode, requestTimestamp);

        try {
            AirportInfo info = aviationProvider.fetchAirportByIcao(normalizedCode);
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            log.info("Airport query succeeded: ICAO_Code={}, requestTimestamp={}, status=200, durationMs={}",
                    normalizedCode, requestTimestamp, durationMs);

            return AirportResponse.builder()
                    .icaoCode(info.getIcaoCode())
                    .faaIdent(info.getFaaIdent())
                    .airportName(info.getAirportName())
                    .city(info.getCity())
                    .stateAbbr(info.getStateAbbr())
                    .stateFull(info.getStateFull())
                    .country(info.getCountry())
                    .isMilitary(info.getIsMilitary())
                    .build();
        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            log.error("Airport query failed: ICAO_Code={}, requestTimestamp={}, status=error, durationMs={}, error={}",
                    normalizedCode, requestTimestamp, durationMs, ex.getMessage());

            throw ex;
        }
    }
}
