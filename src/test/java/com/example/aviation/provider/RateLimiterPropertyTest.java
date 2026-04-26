package com.example.aviation.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 7: Rate limiter rejects requests exceeding the threshold.
 *
 * For any request sequence that exceeds the configured rate limit threshold
 * within a single refresh period, the excess requests should be immediately
 * rejected with HTTP 429 Too Many Requests, and the response should include
 * a Retry-After header. The response format should conform to the unified
 * error response structure.
 *
 * Uses @SpringBootTest with TestRestTemplate to hit the actual controller
 * endpoint (GET /api/v1/airports/KJFK), so the GlobalExceptionHandler
 * handles RequestNotPermitted and returns 429 with Retry-After header.
 *
 * **Validates: Requirements 12.1, 12.2**
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cache.type=none")
class RateLimiterPropertyTest {

    static WireMockServer wireMockServer;

    private static final int RATE_LIMIT = 5;
    private static final String ICAO_CODE = "KJFK";
    private static final String ENDPOINT_PREFIX = "/api/v1/airports/";

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

    static {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired(required = false)
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("aviation.api.base-url", () -> "http://localhost:" + wireMockServer.port());
        // Disable cache so every request hits the rate limiter
        registry.add("spring.cache.type", () -> "none");
        // Low rate limit for testing
        registry.add("aviation.api.rate-limit-for-period", () -> String.valueOf(RATE_LIMIT));
        // Long refresh period so the limit doesn't refresh during the test
        registry.add("aviation.api.rate-limit-refresh-period-ms", () -> "10000");
        // Immediate rejection (timeout = 0)
        registry.add("aviation.api.rate-limit-timeout-ms", () -> "0");
        // Disable retry to avoid interference (1 attempt = no retry)
        registry.add("aviation.api.max-retries", () -> "1");
        // Disable circuit breaker interference
        registry.add("aviation.api.cb-sliding-window-size", () -> "100");
        registry.add("aviation.api.cb-failure-rate-threshold", () -> "100");
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();

        // Clear cache to ensure every request hits the rate limiter
        if (cacheManager != null) {
            cacheManager.getCacheNames().forEach(name -> {
                var cache = cacheManager.getCache(name);
                if (cache != null) cache.clear();
            });
        }

        // Stub WireMock to return success for any airport query
        wireMockServer.stubFor(get(urlPathEqualTo("/charts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(VALID_UPSTREAM_RESPONSE)));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    /**
     * **Validates: Requirements 12.1, 12.2**
     *
     * Property 7: Rate limiter rejects requests exceeding the threshold.
     *
     * Test approach:
     * 1. Send RATE_LIMIT + extra requests in a single refresh period.
     * 2. The first N requests (up to the limit) should succeed with HTTP 200.
     * 3. Subsequent requests beyond the limit should be rejected with HTTP 429.
     * 4. The 429 response must include a Retry-After header.
     * 5. The 429 response body must conform to the unified error format
     *    (contains timestamp, status, error, message, path).
     */
    @Test
    @DisplayName("Requests exceeding rate limit are rejected with 429 and Retry-After header")
    void requestsExceedingRateLimitAreRejectedWith429() {
        int totalRequests = RATE_LIMIT + 3;
        int successCount = 0;
        int rejectedCount = 0;

        // Use unique ICAO codes per request to bypass the Caffeine cache
        String[] icaoCodes = {"KAAA", "KBBB", "KCCC", "KDDD", "KEEE", "KFFF", "KGGG", "KHHH"};

        // Send all requests in rapid succession within a single refresh period
        for (int i = 0; i < totalRequests; i++) {
            String endpoint = ENDPOINT_PREFIX + icaoCodes[i];
            ResponseEntity<String> response = restTemplate.getForEntity(endpoint, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                successCount++;
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                rejectedCount++;

                // Verify Retry-After header is present
                assertThat(response.getHeaders().getFirst("Retry-After"))
                        .as("429 response should include Retry-After header")
                        .isNotNull();

                // Verify response body contains unified error format fields
                String body = response.getBody();
                assertThat(body).as("429 response body should not be null").isNotNull();
                assertThat(body).contains("\"timestamp\"");
                assertThat(body).contains("\"status\"");
                assertThat(body).contains("\"error\"");
                assertThat(body).contains("\"message\"");
                assertThat(body).contains("\"path\"");
                assertThat(body).contains("429");
            }
        }

        // First RATE_LIMIT requests should succeed
        assertThat(successCount)
                .as("First %d requests should succeed (HTTP 200)", RATE_LIMIT)
                .isEqualTo(RATE_LIMIT);

        // Remaining requests should be rejected with 429
        assertThat(rejectedCount)
                .as("Requests beyond the limit should be rejected (HTTP 429)")
                .isEqualTo(3);

        // Verify WireMock only received RATE_LIMIT requests (rejected ones never reach upstream)
        wireMockServer.verify(RATE_LIMIT, getRequestedFor(urlPathEqualTo("/charts")));
    }
}
