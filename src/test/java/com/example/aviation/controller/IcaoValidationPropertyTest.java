package com.example.aviation.controller;

import com.example.aviation.exception.InvalidIcaoCodeException;
import com.example.aviation.service.AirportService;

import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;

import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Property 1: ICAO code validation rejects all invalid formats.
 * Uses jqwik to generate random non-four-letter-alpha strings and verifies
 * all are rejected with InvalidIcaoCodeException without calling the upstream.
 *
 * Validates: Requirements 1.2, 8.1
 */
class IcaoValidationPropertyTest {

    /**
     * **Validates: Requirements 1.2, 8.1**
     *
     * For any string that does NOT match [a-zA-Z]{4}, the controller must
     * throw InvalidIcaoCodeException and never call the AirportService.
     */
    @Property(tries = 100)
    void invalidIcaoCodeAlwaysRejected(@ForAll("invalidIcaoCodes") String input) {
        AirportService mockService = Mockito.mock(AirportService.class);
        AirportController controller = new AirportController(mockService);

        assertThatThrownBy(() -> controller.getAirport(input))
                .isInstanceOf(InvalidIcaoCodeException.class);

        verify(mockService, never()).queryAirport(anyString());
    }

    @Provide
    Arbitrary<String> invalidIcaoCodes() {
        // Combine several categories of invalid ICAO codes
        return Arbitraries.oneOf(
                // Empty string
                Arbitraries.just(""),
                // Too short (1-3 alpha chars)
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(3),
                // Too long (5-10 alpha chars)
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(10),
                // Exactly 4 chars but containing digits
                stringsWithDigits(),
                // Strings with special characters
                Arbitraries.strings().withChars("!@#$%^&*()_+-=[]{}|;:',.<>?/~`").ofMinLength(1).ofMaxLength(8),
                // Mixed alphanumeric of length 4 (must contain at least one non-alpha)
                mixedAlphanumericLength4(),
                // Strings with whitespace
                Arbitraries.strings().withChars("abcd \t\n").ofMinLength(1).ofMaxLength(6)
                        .filter(s -> !s.matches("[a-zA-Z]{4}"))
        );
    }

    private Arbitrary<String> stringsWithDigits() {
        // Generate 4-char strings that contain at least one digit
        return Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz0123456789").ofLength(4)
                .filter(s -> s.matches(".*\\d.*"));
    }

    private Arbitrary<String> mixedAlphanumericLength4() {
        // Generate 4-char strings with alphanumeric chars that aren't purely alpha
        return Arbitraries.strings().withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
                .ofLength(4)
                .filter(s -> !s.matches("[a-zA-Z]{4}"));
    }
}
