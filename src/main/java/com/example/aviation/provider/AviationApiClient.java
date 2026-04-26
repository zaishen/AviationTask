package com.example.aviation.provider;

import com.example.aviation.config.AviationApiProperties;
import com.example.aviation.exception.AirportNotFoundException;
import com.example.aviation.exception.CircuitBreakerOpenException;
import com.example.aviation.exception.UpstreamRateLimitException;
import com.example.aviation.exception.UpstreamServiceException;
import com.example.aviation.model.AirportInfo;
import com.example.aviation.model.upstream.UpstreamAirportData;
import com.example.aviation.model.upstream.UpstreamChartResponse;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Concrete implementation of AviationProvider. Sends HTTP requests to the upstream
 * aviationapi.com API and maps responses to internal domain models via the Adapter Pattern.
 * Resilience4j annotations apply the Decorator Pattern to layer resilience concerns.
 */
@Component
public class AviationApiClient implements AviationProvider {

    private static final Logger log = LoggerFactory.getLogger(AviationApiClient.class);

    private final RestTemplate restTemplate;
    private final AviationApiProperties properties;

    public AviationApiClient(RestTemplate restTemplate, AviationApiProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    @RateLimiter(name = "upstreamApi")
    @CircuitBreaker(name = "upstreamApi", fallbackMethod = "circuitBreakerFallback")
    @Retry(name = "upstreamApi")
    public AirportInfo fetchAirportByIcao(String icaoCode) {
        String url = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl())
                .path("/charts")
                .queryParam("airport", icaoCode)
                .toUriString();
        long startTime = System.nanoTime();

        log.info("Sending request to upstream API: url={}", url);

        try {
            ResponseEntity<UpstreamChartResponse> response =
                    restTemplate.getForEntity(url, UpstreamChartResponse.class);

            log.info("Upstream API response: url={}, statusCode={}, elapsedMs={}",
                    url, response.getStatusCode().value(), elapsedSince(startTime));

            if (response.getBody() == null || response.getBody().getAirportData() == null) {
                throw new AirportNotFoundException(icaoCode);
            }

            return adaptToAirportInfo(response.getBody().getAirportData());

        } catch (HttpClientErrorException e) {
            log.warn("Upstream API client error: url={}, statusCode={}, elapsedMs={}",
                    url, e.getStatusCode().value(), elapsedSince(startTime));

            if (e.getStatusCode().value() == 429) {
                Long retryAfter = parseRetryAfterHeader(e.getResponseHeaders());
                throw new UpstreamRateLimitException(retryAfter, e);
            }
            throw new UpstreamServiceException(e.getStatusCode().value(), e.getStatusText(), e);

        } catch (HttpServerErrorException e) {
            log.error("Upstream API server error: url={}, statusCode={}, elapsedMs={}",
                    url, e.getStatusCode().value(), elapsedSince(startTime));
            throw new UpstreamServiceException(e.getStatusCode().value(), e.getStatusText(), e);

        } catch (ResourceAccessException e) {
            log.error("Upstream API timeout/connection error: url={}, elapsedMs={}, message={}",
                    url, elapsedSince(startTime), e.getMessage());
            throw new UpstreamServiceException(0, "Connection or read timeout: " + e.getMessage(), e);
        }
    }

    /**
     * Adapter method: converts upstream airport_data response to internal domain model.
     * Package-private access for testability.
     */
    AirportInfo adaptToAirportInfo(UpstreamAirportData upstream) {
        return AirportInfo.builder()
                .icaoCode(upstream.getIcaoIdent())
                .faaIdent(upstream.getFaaIdent())
                .airportName(upstream.getAirportName())
                .city(upstream.getCity())
                .stateAbbr(upstream.getStateAbbr())
                .stateFull(upstream.getStateFull())
                .country(upstream.getCountry())
                .isMilitary(upstream.getIsMilitary())
                .build();
    }

    /**
     * Circuit breaker fallback method.
     * Only wraps as CircuitBreakerOpenException when the circuit breaker is actually open
     * (CallNotPermittedException). For other exceptions, re-throw the original to allow
     * the Retry decorator to handle them.
     */
    private AirportInfo circuitBreakerFallback(String icaoCode, Throwable t) {
        if (t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            log.warn("Circuit breaker open for upstreamApi, icaoCode={}, cause={}", icaoCode, t.getMessage());
            throw new CircuitBreakerOpenException("upstreamApi", t);
        }
        // Re-throw the original exception so the Retry decorator can handle it
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        throw new UpstreamServiceException(0, t.getMessage(), t);
    }

    private long elapsedSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private Long parseRetryAfterHeader(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter == null) {
            return null;
        }
        try {
            return Long.parseLong(retryAfter);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
