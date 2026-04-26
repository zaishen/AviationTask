package com.example.aviation.provider;

import com.example.aviation.exception.CircuitBreakerOpenException;
import com.example.aviation.exception.UpstreamServiceException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import net.jqwik.api.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property 4: Circuit breaker opens when failure rate reaches threshold.
 *
 * For any sliding window of requests, when the failure rate reaches or exceeds 50%,
 * the circuit breaker should transition to OPEN state, and all subsequent requests
 * should fail immediately with HTTP 503 without hitting the upstream API.
 *
 * Uses @SpringBootTest with WireMock. Retry is disabled (max-attempts=1) to isolate
 * circuit breaker behavior. Sliding window size is set to 10 with 50% failure threshold.
 *
 * **Validates: Requirements 4.1, 4.2**
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CircuitBreakerPropertyTest {

    static WireMockServer wireMockServer;

    static {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @Autowired
    private AviationApiClient aviationApiClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private static final int SLIDING_WINDOW_SIZE = 10;
    private static final String ICAO_CODE = "KJFK";

    private static final String VALID_UPSTREAM_RESPONSE = """
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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aviation.api.base-url", () -> "http://localhost:" + wireMockServer.port());
        // Disable retry to isolate circuit breaker behavior (1 attempt = no retry)
        registry.add("aviation.api.max-retries", () -> "1");
        // Circuit breaker config: sliding window of 10, 50% failure rate threshold
        registry.add("aviation.api.cb-sliding-window-size", () -> String.valueOf(SLIDING_WINDOW_SIZE));
        registry.add("aviation.api.cb-failure-rate-threshold", () -> "50");
        registry.add("aviation.api.cb-wait-duration-in-open-state-ms", () -> "60000");
        // High rate limit to avoid interference
        registry.add("aviation.api.rate-limit-for-period", () -> "1000");
        registry.add("aviation.api.rate-limit-timeout-ms", () -> "5000");
    }

    @BeforeEach
    void resetWireMockAndCircuitBreaker() {
        wireMockServer.resetAll();
        // Reset circuit breaker to CLOSED state before each test
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("upstreamApi");
        cb.reset();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    /**
     * **Validates: Requirements 4.1, 4.2**
     *
     * Property 4: Circuit breaker opens when failure rate reaches threshold.
     *
     * Test approach:
     * 1. Fill the sliding window with requests where >50% are failures (5xx).
     * 2. After the window fills and failure rate exceeds 50%, verify the circuit breaker
     *    transitions to OPEN state.
     * 3. Verify subsequent requests get CircuitBreakerOpenException (503) without
     *    hitting WireMock.
     */
    @Test
    @DisplayName("Circuit breaker opens when failure rate exceeds 50% in sliding window")
    void circuitBreakerOpensWhenFailureRateExceedsThreshold() {
        // Step 1: Configure WireMock to return failures for all requests
        // We send all failures to guarantee >50% failure rate
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}")));

        // Step 2: Fill the sliding window with failures
        for (int i = 0; i < SLIDING_WINDOW_SIZE; i++) {
            try {
                aviationApiClient.fetchAirportByIcao(ICAO_CODE);
            } catch (UpstreamServiceException e) {
                // Expected — these are the failures filling the window
            }
        }

        // Step 3: Verify circuit breaker is now OPEN
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("upstreamApi");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Step 4: Record WireMock request count before the next call
        int requestCountBefore = wireMockServer.countRequestsMatching(
                getRequestedFor(urlPathEqualTo("/charts")).build()).getCount();

        // Step 5: Verify subsequent request gets CircuitBreakerOpenException (503)
        Throwable thrown = catchThrowable(() -> aviationApiClient.fetchAirportByIcao(ICAO_CODE));
        assertThat(thrown).isInstanceOf(CircuitBreakerOpenException.class);

        // Step 6: Verify WireMock was NOT called (circuit breaker short-circuited)
        int requestCountAfter = wireMockServer.countRequestsMatching(
                getRequestedFor(urlPathEqualTo("/charts")).build()).getCount();
        assertThat(requestCountAfter).isEqualTo(requestCountBefore);
    }

    /**
     * **Validates: Requirements 4.1, 4.2**
     *
     * Verify that when failure rate is exactly at the threshold (50%),
     * the circuit breaker opens. Mix of success and failure responses.
     */
    @Test
    @DisplayName("Circuit breaker opens when failure rate is exactly 50%")
    void circuitBreakerOpensAtExactThreshold() {
        // We need exactly 50% failures in the sliding window of 10
        // Send 5 successes first, then 5 failures
        // WireMock scenario-based stubbing to alternate responses

        // First: stub success responses
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VALID_UPSTREAM_RESPONSE))
                .willSetStateTo("success-1"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("success-1")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VALID_UPSTREAM_RESPONSE))
                .willSetStateTo("success-2"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("success-2")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VALID_UPSTREAM_RESPONSE))
                .willSetStateTo("success-3"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("success-3")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VALID_UPSTREAM_RESPONSE))
                .willSetStateTo("success-4"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("success-4")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VALID_UPSTREAM_RESPONSE))
                .willSetStateTo("fail-phase"));

        // Then: stub failure responses for the remaining 5
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("fail-phase")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}"))
                .willSetStateTo("fail-1"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("fail-1")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}"))
                .willSetStateTo("fail-2"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("fail-2")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}"))
                .willSetStateTo("fail-3"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("fail-3")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}"))
                .willSetStateTo("fail-4"));

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(ICAO_CODE))
                .inScenario("mixed")
                .whenScenarioStateIs("fail-4")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}"))
                .willSetStateTo("all-done"));

        // Send 5 successful requests
        for (int i = 0; i < 5; i++) {
            aviationApiClient.fetchAirportByIcao(ICAO_CODE);
        }

        // Send 5 failing requests
        for (int i = 0; i < 5; i++) {
            try {
                aviationApiClient.fetchAirportByIcao(ICAO_CODE);
            } catch (UpstreamServiceException e) {
                // Expected
            }
        }

        // After 10 requests with 50% failure rate, circuit breaker should be OPEN
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("upstreamApi");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Subsequent request should get CircuitBreakerOpenException
        int requestCountBefore = wireMockServer.countRequestsMatching(
                getRequestedFor(urlPathEqualTo("/charts")).build()).getCount();

        Throwable thrown = catchThrowable(() -> aviationApiClient.fetchAirportByIcao(ICAO_CODE));
        assertThat(thrown).isInstanceOf(CircuitBreakerOpenException.class);

        // Verify no additional WireMock call was made
        int requestCountAfter = wireMockServer.countRequestsMatching(
                getRequestedFor(urlPathEqualTo("/charts")).build()).getCount();
        assertThat(requestCountAfter).isEqualTo(requestCountBefore);
    }

    /**
     * **Validates: Requirements 4.1, 4.2**
     *
     * Verify that when failure rate is below the threshold (<50%),
     * the circuit breaker stays CLOSED and requests continue to go through.
     */
    @Test
    @DisplayName("Circuit breaker stays closed when failure rate is below 50%")
    void circuitBreakerStaysClosedBelowThreshold() {
        // 4 failures + 6 successes = 40% failure rate (below 50%)
        // Use scenario to send 4 failures then 6 successes

        String[] states = {"Started", "f1", "f2", "f3", "s1", "s2", "s3", "s4", "s5", "s6", "done"};

        // 4 failures
        for (int i = 0; i < 4; i++) {
            wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                    .withQueryParam("airport", equalTo(ICAO_CODE))
                    .inScenario("below-threshold")
                    .whenScenarioStateIs(states[i])
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\": \"Server Error\"}"))
                    .willSetStateTo(states[i + 1]));
        }

        // 6 successes
        for (int i = 4; i < 10; i++) {
            wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                    .withQueryParam("airport", equalTo(ICAO_CODE))
                    .inScenario("below-threshold")
                    .whenScenarioStateIs(states[i])
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(VALID_UPSTREAM_RESPONSE))
                    .willSetStateTo(states[i + 1]));
        }

        // Send 4 failing requests
        for (int i = 0; i < 4; i++) {
            try {
                aviationApiClient.fetchAirportByIcao(ICAO_CODE);
            } catch (UpstreamServiceException e) {
                // Expected
            }
        }

        // Send 6 successful requests
        for (int i = 0; i < 6; i++) {
            aviationApiClient.fetchAirportByIcao(ICAO_CODE);
        }

        // Circuit breaker should still be CLOSED (40% < 50%)
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("upstreamApi");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
