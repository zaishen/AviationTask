package com.example.aviation.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "aviation.api")
@Validated
@Getter
@Setter
public class AviationApiProperties {

    @NotBlank
    private String baseUrl = "https://api-v2.aviationapi.com/v2";

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 5000;

    private int maxRetries = 3;

    private long retryInitialIntervalMs = 500;

    private double retryMultiplier = 2.0;

    // Circuit Breaker
    private int cbSlidingWindowSize = 10;

    private int cbFailureRateThreshold = 50;

    private long cbWaitDurationInOpenStateMs = 30000;

    private int cbPermittedCallsInHalfOpenState = 1;

    // Rate Limiter
    private int rateLimitForPeriod = 10;

    private long rateLimitRefreshPeriodMs = 1000;

    private long rateLimitTimeoutMs = 0;

    // Cache
    private long cacheExpireAfterWriteMinutes = 60;

    private long cacheMaximumSize = 1000;

    // Connection Pool
    private int poolMaxTotal = 50;

    private int poolMaxPerRoute = 20;
}
