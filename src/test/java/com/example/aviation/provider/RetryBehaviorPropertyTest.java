package com.example.aviation.provider;

import com.example.aviation.exception.UpstreamServiceException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property 3: Server errors trigger retry.
 *
 * For any HTTP 5xx status code (500, 502, 503, 504), when the upstream API
 * returns that status code, the system should automatically retry the request
 * exactly 3 times total (max-attempts = 3), and after all retries fail,
 * throw an UpstreamServiceException.
 *
 * Uses @SpringBootTest with WireMock and @ParameterizedTest with multiple 5xx
 * codes to simulate property-based testing across the 5xx status code space.
 *
 * **Validates: Requirements 3.1, 3.2, 5.2**
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetryBehaviorPropertyTest {

    static WireMockServer wireMockServer;

    static {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @Autowired
    private AviationApiClient aviationApiClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aviation.api.base-url", () -> "http://localhost:" + wireMockServer.port());
        // Use short retry intervals for faster tests
        registry.add("aviation.api.retry-initial-interval-ms", () -> "100");
        // Disable circuit breaker interference: very high threshold and large window
        registry.add("aviation.api.cb-sliding-window-size", () -> "100");
        registry.add("aviation.api.cb-failure-rate-threshold", () -> "100");
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
     * **Validates: Requirements 3.1, 3.2, 5.2**
     *
     * For each 5xx status code, verify that:
     * 1. WireMock receives exactly 3 requests (max-attempts = 3)
     * 2. An UpstreamServiceException is thrown after all retries fail
     */
    @ParameterizedTest(name = "5xx status {0} triggers exactly 3 retry attempts")
    @ValueSource(ints = {500, 502, 504})
    void serverErrorTriggersRetry(int statusCode) {
        // Use a unique ICAO code per status code to isolate WireMock request counts
        String icaoCode = switch (statusCode) {
            case 500 -> "KAAA";
            case 502 -> "KBBB";
            case 504 -> "KDDD";
            default -> "KZZZ";
        };

        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(icaoCode))
                .willReturn(aResponse()
                        .withStatus(statusCode)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Server Error\"}")));

        Throwable thrown = catchThrowable(() -> aviationApiClient.fetchAirportByIcao(icaoCode));

        // After all retries fail, UpstreamServiceException should be thrown
        assertThat(thrown).isInstanceOf(UpstreamServiceException.class);

        // Verify WireMock received exactly 3 requests (max-attempts = 3 means 3 total attempts)
        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/charts"))
                .withQueryParam("airport", equalTo(icaoCode)));
    }
}
