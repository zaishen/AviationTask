package com.example.aviation.controller;

import com.example.aviation.exception.InvalidIcaoCodeException;
import com.example.aviation.service.AirportService;

import net.jqwik.api.*;

import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Property 6: Path traversal and special character rejection.
 * Uses jqwik to generate random strings containing path traversal sequences
 * or special characters, and verifies all are rejected with InvalidIcaoCodeException.
 *
 * Validates: Requirements 8.3
 */
class PathTraversalPropertyTest {

    private static final String[] DANGEROUS_PATTERNS = {
            "../", "..%2f", "..%2F", "%2e%2e", "%2E%2E", ";", "<", ">", "|"
    };

    /**
     * **Validates: Requirements 8.3**
     *
     * For any string that contains a path traversal sequence or special character,
     * the controller must throw InvalidIcaoCodeException and never call the AirportService.
     */
    @Property(tries = 100)
    void pathTraversalAndSpecialCharsAlwaysRejected(@ForAll("dangerousInputs") String input) {
        AirportService mockService = Mockito.mock(AirportService.class);
        AirportController controller = new AirportController(mockService);

        assertThatThrownBy(() -> controller.getAirport(input))
                .isInstanceOf(InvalidIcaoCodeException.class);

        verify(mockService, never()).queryAirport(anyString());
    }

    @Provide
    Arbitrary<String> dangerousInputs() {
        // Pick a random dangerous pattern and wrap it with optional random prefix/suffix
        Arbitrary<String> pattern = Arbitraries.of(DANGEROUS_PATTERNS);
        Arbitrary<String> prefix = Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(5);
        Arbitrary<String> suffix = Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(5);

        return Combinators.combine(prefix, pattern, suffix)
                .as((p, d, s) -> p + d + s);
    }
}
