package com.example.aviation.exception;

import lombok.Getter;

/**
 * Thrown when the upstream API returns an error response (e.g. 5xx)
 * after all retries have been exhausted.
 */
@Getter
public class UpstreamServiceException extends AviationBaseException {

    private final int upstreamStatusCode;
    private final String upstreamMessage;

    public UpstreamServiceException(int upstreamStatusCode, String upstreamMessage) {
        super("Upstream service error: HTTP " + upstreamStatusCode + " - " + upstreamMessage);
        this.upstreamStatusCode = upstreamStatusCode;
        this.upstreamMessage = upstreamMessage;
    }

    public UpstreamServiceException(int upstreamStatusCode, String upstreamMessage, Throwable cause) {
        super("Upstream service error: HTTP " + upstreamStatusCode + " - " + upstreamMessage, cause);
        this.upstreamStatusCode = upstreamStatusCode;
        this.upstreamMessage = upstreamMessage;
    }
}
