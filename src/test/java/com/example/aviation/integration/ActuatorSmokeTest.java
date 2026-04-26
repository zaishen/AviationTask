package com.example.aviation.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator smoke tests — verify health, metrics, and readiness endpoints are accessible
 * and that security headers are present on actuator responses.
 *
 * Validates: Requirements 7.3, 7.4, 9.3
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpoint_returns200() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\"");
    }

    @Test
    void metricsEndpoint_returns200() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"names\"");
    }

    @Test
    void readinessEndpoint_returns200() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\"");
    }

    @Test
    void actuatorHealth_containsSecurityHeaders() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void actuatorMetrics_containsSecurityHeaders() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/metrics", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
}
