package com.example.aviation.controller;

import com.example.aviation.exception.InvalidIcaoCodeException;
import com.example.aviation.model.AirportResponse;
import com.example.aviation.service.AirportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/airports")
public class AirportController {

    private static final Logger log = LoggerFactory.getLogger(AirportController.class);

    private static final Pattern ICAO_PATTERN = Pattern.compile("^[a-zA-Z]{4}$");

    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
            "(?i)(\\.\\./|\\.\\.%2[fF]|%2[eE]%2[eE]|[;<>|])"
    );

    private final AirportService airportService;

    public AirportController(AirportService airportService) {
        this.airportService = airportService;
    }

    @GetMapping("/{icaoCode}")
    public ResponseEntity<AirportResponse> getAirport(@PathVariable String icaoCode) {
        // 1. Defense-in-depth: reject path traversal and special characters early.
        if (PATH_TRAVERSAL_PATTERN.matcher(icaoCode).find()) {
            log.warn("Path traversal or special character detected in ICAO code: {}", icaoCode);
            throw new InvalidIcaoCodeException(icaoCode);
        }

        // 2. Validate ICAO format: exactly 4 alphabetic characters (a-z, A-Z)
        if (!ICAO_PATTERN.matcher(icaoCode).matches()) {
            log.warn("Invalid ICAO code format: {}", icaoCode);
            throw new InvalidIcaoCodeException(icaoCode);
        }

        // 3. Delegate to service
        AirportResponse response = airportService.queryAirport(icaoCode);
        return ResponseEntity.ok(response);
    }
}
