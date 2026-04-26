# Aviation API Wrapper

A Spring Boot microservice that wraps the public [Aviation API](https://api-v2.aviationapi.com/v2) to provide airport information lookups by ICAO code.

Given an ICAO airport code (e.g. `KJFK`), the service fetches airport details from the upstream API and returns a clean JSON response with the airport's name, location, FAA identifier, and military status.


## Setup and Run

### Prerequisites

- Java 17+
- Maven 3.9+
- Docker (optional)

### Run with Maven

```bash
cd aviation
mvn clean package
mvn spring-boot:run
```

The service starts at `http://localhost:8080`.

### Run with Docker

```bash
cd aviation
docker build -t aviation-api-wrapper .
docker run -p 8080:8080 aviation-api-wrapper
```

### Try it out

```bash
# Query an airport
curl -s http://localhost:8080/api/v1/airports/KJFK | jq

# Health check
curl -s http://localhost:8080/actuator/health | jq
```

Sample response:

```json
{
  "icaoCode": "KJFK",
  "faaIdent": "JFK",
  "airportName": "JOHN F KENNEDY INTL",
  "city": "NEW YORK",
  "stateAbbr": "NY",
  "stateFull": "NEW YORK",
  "country": "USA",
  "isMilitary": false
}
```

### Configuration

All settings live in `application.yml` and can be overridden via environment variables:

```bash
# Example: change upstream URL and rate limit
docker run -p 8080:8080 \
  -e AVIATION_API_BASE_URL=https://api-v2.aviationapi.com/v2 \
  -e AVIATION_API_RATE_LIMIT_FOR_PERIOD=20 \
  aviation-api-wrapper
```


## Running Tests

```bash
cd aviation

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=AirportApiIntegrationTest
```

The test suite covers three layers:

- **Property-based tests** (jqwik, 100 iterations each) — verify correctness properties hold for *any* valid input, not just hand-picked examples. Covers ICAO validation, adapter field mapping, error response format, and path traversal rejection.
- **Integration tests** (WireMock) — spin up the full Spring context with a mock upstream API. Tests the happy path, retry-then-succeed flow, invalid input handling, and security header presence.
- **Resilience tests** — verify retry behavior across different 5xx codes, circuit breaker state transitions (open/closed/half-open), and rate limiter rejection with `Retry-After` headers.
- **Smoke tests** — confirm `/actuator/health`, `/actuator/metrics`, and `/actuator/health/readiness` are accessible and return security headers.

No external services are needed to run tests. WireMock simulates the upstream API entirely in-process.


## Assumptions and Architecture Decisions

### Upstream API

- The upstream API at `api-v2.aviationapi.com` is a free, public FAA data source. It does **not** require an API key.
- It primarily covers **US airports**. Non-US ICAO codes (e.g. `RCTP`, `EGLL`) return 500 from the upstream — this is an upstream limitation, not a bug in this service.
- The endpoint used is `GET /charts?airport={icaoCode}`, which returns airport metadata nested inside an `airport_data` object.

### Architecture

The service uses a three-layer architecture:

```
Controller → Service → Provider (interface) → ApiClient (implementation)
```

- **`AviationProvider` interface** abstracts the upstream data source. Swapping to a different aviation API means implementing one interface — no changes to the service or controller.
- **`AviationApiClient`** handles HTTP calls, response mapping, and is decorated with Resilience4j annotations for retry, circuit breaking, and rate limiting.
- **`AirportService`** is the business logic layer. It normalizes the ICAO code, delegates to the provider, and builds the response. Results are cached locally with Caffeine (1 hour TTL, up to 1000 entries) since airport data rarely changes.

### Resilience

All resilience is handled by Resilience4j via annotations, configured in `application.yml`:

- **Retry**: 3 attempts with exponential backoff (500ms × 2.0) on 5xx errors and timeouts.
- **Circuit breaker**: Opens after 50% failure rate in a sliding window of 10 requests. Stays open for 30 seconds, then allows 1 probe request.
- **Rate limiter**: 10 requests/second to protect the upstream API. Excess requests get an immediate 429 with `Retry-After` header.
- **Timeouts**: 3s connection timeout, 5s read timeout via Apache HttpClient 5 connection pool (50 max connections, 20 per route).

### Input Validation and Security

- ICAO codes are validated with a strict regex (`^[a-zA-Z]{4}$`). Anything else gets a 400 before any upstream call is made.
- A separate path traversal check (`../`, `%2e%2e`, `;`, `<`, `>`, `|`) runs first as defense-in-depth — the ICAO regex would catch these too, but the explicit check provides clear security logging.
- All responses include `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Cache-Control: no-store`.


## Error Handling

Every error response uses the same JSON structure:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid ICAO code: XX",
  "path": "/api/v1/airports/XX"
}
```

The `message` field never exposes stack traces, class names, or internal details. For 500/502/503 errors, only a generic message is returned — full details are logged server-side.

| Scenario | Status | What happens |
|----------|--------|-------------|
| Invalid ICAO format | 400 | Rejected at controller; upstream never called |
| Path traversal attempt | 400 | Rejected at controller; logged as security event |
| Airport not found | 404 | Upstream returned empty `airport_data` |
| Rate limit exceeded | 429 | `Retry-After: 1` header included |
| Upstream rate limit | 429 | Upstream's `Retry-After` value forwarded |
| Upstream 5xx (retries exhausted) | 502 | Generic message: "Upstream service is temporarily unavailable" |
| Circuit breaker open | 503 | Generic message: "Service temporarily unavailable" |
| Unexpected error | 500 | Generic message: "Internal server error" |

The exception hierarchy (`AviationBaseException` → specific subtypes) is handled by a single `@RestControllerAdvice` class with dedicated `@ExceptionHandler` methods for each type.


## AI-Generated Code Detail

This task was generated with AI assistance using [Kiro](https://kiro.dev). Below are the implementation details.

### Upstream Response Mapping

The upstream API returns airport data nested inside an `airport_data` object with snake_case field names (`icao_ident`, `faa_ident`, `airport_name`, `state_abbr`, `state_full`, `is_military`). The `UpstreamAirportData` class uses `@JsonProperty` annotations to map these to Java camelCase fields. A dedicated `adaptToAirportInfo()` method in `AviationApiClient` converts this upstream model into the internal `AirportInfo` domain object, so upstream format changes only affect one place.

### Resilience4j Annotation Order

The annotations on `fetchAirportByIcao()` are ordered `@RateLimiter → @CircuitBreaker → @Retry`. Resilience4j processes them innermost-first, so the actual execution order is: Retry wraps CircuitBreaker wraps RateLimiter wraps the HTTP call. This means rate limiting is checked first, then the circuit breaker, and retry is the outermost layer that re-attempts the entire decorated chain.

### Circuit Breaker Fallback

The `circuitBreakerFallback` method distinguishes between a genuinely open circuit breaker (`CallNotPermittedException`) and other exceptions that pass through the circuit breaker. Only `CallNotPermittedException` is wrapped as `CircuitBreakerOpenException` (→ 503). All other exceptions are re-thrown as-is so the retry decorator can handle them normally.

### Caching Strategy

`@Cacheable` is placed on `AirportService.queryAirport()` — above the provider layer. This means cached responses bypass the rate limiter and circuit breaker entirely, which is intentional: a cache hit should be instant and shouldn't consume rate limit quota. The cache key is the uppercased ICAO code, so `kjfk` and `KJFK` share the same cache entry.

### HTTP Client

`RestTemplate` is backed by Apache HttpClient 5 with a `PoolingHttpClientConnectionManager` instead of the default `SimpleClientHttpRequestFactory`. This enables TCP connection reuse via keep-alive, reducing handshake overhead under load. Pool sizes (50 total, 20 per route) and timeouts are externalized in `AviationApiProperties`.

### Security Header Filter

A `OncePerRequestFilter` registered via `FilterRegistrationBean` adds security headers to every response, including error responses and actuator endpoints. This is more reliable than adding headers in the controller or exception handler, since it covers all response paths.

### Structured Logging

Both `AirportService` and `AviationApiClient` log with structured key-value pairs (ICAO code, request timestamp, status code, duration in ms). The service layer uses `System.nanoTime()` for timing precision. Error logs in `GlobalExceptionHandler` include the full exception for server-side debugging but never expose these details in the HTTP response.
