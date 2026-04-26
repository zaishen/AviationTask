package com.example.aviation.exception;

import lombok.Getter;

/**
 * Thrown when the upstream API returns HTTP 429 Too Many Requests.
 */
@Getter
public class UpstreamRateLimitException extends AviationBaseException {

    private final Long retryAfterSeconds;

    public UpstreamRateLimitException(Long retryAfterSeconds) {
        super("Upstream rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public UpstreamRateLimitException(Long retryAfterSeconds, Throwable cause) {
        super("Upstream rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.", cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
