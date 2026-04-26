package com.example.aviation.integration;

import com.example.aviation.model.AirportResponse;
import com.example.aviation.model.ErrorResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Airport API endpoint using WireMock to simulate the upstream API.
 *
 * Validates: Requirements 10.1, 10.2, 10.3, 8.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AirportApiIntegrationTest {

    static WireMockServer wireMockServer;

    static {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aviation.api.base-url", () -> "http://localhost:" + wireMockServer.port());
        // Disable circuit breaker interference
        registry.add("aviation.api.cb-sliding-window-size", () -> "100");
        registry.add("aviation.api.cb-failure-rate-threshold", () -> "100");
        registry.add("aviation.api.cb-wait-duration-in-open-state-ms", () -> "60000");
        // High rate limit to avoid interference
        registry.add("aviation.api.rate-limit-for-period", () -> "1000");
        registry.add("aviation.api.rate-limit-timeout-ms", () -> "5000");
        // Low retry interval for faster tests
        registry.add("aviation.api.retry-initial-interval-ms", () -> "100");
    }

    private static final String UPSTREAM_SUCCESS_JSON = """
            {
                "airport_data": {
                    "icao_ident": "KJFK",
                    "faa_ident": "JFK",
                    "airport_name": "JOHN F KENNEDY INTL",
                    "city": "NEW YORK",
                    "state_abbr": "NY",
                    "state_full": "NEW YORK",
                    "country": "USA",
                    "is_military": false
                },
                "charts": {}
            }
            """;

    @BeforeEach
    void resetWireMockAndCircuitBreaker() {
        wireMockServer.resetAll();
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("upstreamApi");
        cb.reset();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    // ========== Happy Path ==========

    /**
     * Validates: Requirement 10.1
     * WireMock returns valid airport data → verify 200 + correct JSON fields.
     */
    @Test
    void happyPath_validIcaoCode_returnsAirportResponse() {
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo("KJFK"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_SUCCESS_JSON)));

        ResponseEntity<AirportResponse> response =
                restTemplate.getForEntity("/api/v1/airports/KJFK", AirportResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AirportResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getIcaoCode()).isEqualTo("KJFK");
        assertThat(body.getFaaIdent()).isEqualTo("JFK");
        assertThat(body.getAirportName()).isEqualTo("JOHN F KENNEDY INTL");
        assertThat(body.getCity()).isEqualTo("NEW YORK");
        assertThat(body.getStateAbbr()).isEqualTo("NY");
        assertThat(body.getStateFull()).isEqualTo("NEW YORK");
        assertThat(body.getCountry()).isEqualTo("USA");
        assertThat(body.getIsMilitary()).isFalse();
    }

    // ========== Retry Flow ==========

    /**
     * Validates: Requirement 10.2
     * WireMock returns 500 twice then 200 → verify retry mechanism succeeds.
     */
    @Test
    void retryFlow_upstreamFailsThenSucceeds_returnsSuccessAfterRetry() {
        // Use WireMock scenarios to simulate: 500 → 500 → 200
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo("KJFK"))
                .inScenario("retry-test")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"Server Error\"}"))
                .willSetStateTo("first-failure"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo("KJFK"))
                .inScenario("retry-test")
                .whenScenarioStateIs("first-failure")
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"Server Error\"}"))
                .willSetStateTo("second-failure"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo("KJFK"))
                .inScenario("retry-test")
                .whenScenarioStateIs("second-failure")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_SUCCESS_JSON)));

        ResponseEntity<AirportResponse> response =
                restTemplate.getForEntity("/api/v1/airports/KJFK", AirportResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getIcaoCode()).isEqualTo("KJFK");

        // Verify WireMock received exactly 3 requests (2 failures + 1 success)
        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo("KJFK")));
    }

    // ========== Invalid Input ==========

    /**
     * Validates: Requirement 10.3
     * Invalid ICAO code "123" → verify HTTP 400 + unified error format.
     */
    @Test
    void invalidInput_nonAlphabeticIcao_returns400WithUnifiedErrorFormat() {
        ResponseEntity<ErrorResponse> response =
                restTemplate.getForEntity("/api/v1/airports/123", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getError()).isEqualTo("Bad Request");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getPath()).isEqualTo("/api/v1/airports/123");
        assertThat(body.getTimestamp()).isNotNull();

        // Verify no request was sent to upstream
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/charts")));
    }

    // ========== Security Headers ==========

    /**
     * Validates: Requirement 8.2
     * Verify all success responses include security headers.
     */
    @Test
    void securityHeaders_successResponse_containsAllRequiredHeaders() {
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo("KJFK"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(UPSTREAM_SUCCESS_JSON)));

        ResponseEntity<AirportResponse> response =
                restTemplate.getForEntity("/api/v1/airports/KJFK", AirportResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    /**
     * Validates: Requirement 8.2
     * Verify error responses (400) also include security headers.
     */
    @Test
    void securityHeaders_errorResponse_containsAllRequiredHeaders() {
        ResponseEntity<ErrorResponse> response =
                restTemplate.getForEntity("/api/v1/airports/123", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
}
