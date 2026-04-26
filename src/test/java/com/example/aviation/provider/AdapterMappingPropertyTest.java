package com.example.aviation.provider;

import com.example.aviation.model.AirportInfo;
import com.example.aviation.model.upstream.UpstreamAirportData;

import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 2: Adapter mapping preserves all fields.
 * Uses jqwik to generate random UpstreamAirportData objects and verifies that
 * adaptToAirportInfo() produces an AirportInfo with every field matching the original.
 *
 * Validates: Requirements 2.2
 */
class AdapterMappingPropertyTest {

    private final AviationApiClient client = new AviationApiClient(null, null);

    /**
     * **Validates: Requirements 2.2**
     */
    @Property(tries = 100)
    void adapterPreservesAllFields(@ForAll("randomUpstreamAirportData") UpstreamAirportData upstream) {
        AirportInfo result = client.adaptToAirportInfo(upstream);

        assertThat(result.getIcaoCode()).isEqualTo(upstream.getIcaoIdent());
        assertThat(result.getFaaIdent()).isEqualTo(upstream.getFaaIdent());
        assertThat(result.getAirportName()).isEqualTo(upstream.getAirportName());
        assertThat(result.getCity()).isEqualTo(upstream.getCity());
        assertThat(result.getStateAbbr()).isEqualTo(upstream.getStateAbbr());
        assertThat(result.getStateFull()).isEqualTo(upstream.getStateFull());
        assertThat(result.getCountry()).isEqualTo(upstream.getCountry());
        assertThat(result.getIsMilitary()).isEqualTo(upstream.getIsMilitary());
    }

    @Provide
    Arbitrary<UpstreamAirportData> randomUpstreamAirportData() {
        Arbitrary<String> strings = Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(50);
        Arbitrary<Boolean> booleans = Arbitraries.of(true, false, null);

        return Combinators.combine(
                strings, // city
                strings, // stateAbbr
                strings, // stateFull
                strings, // country
                strings, // icaoIdent
                strings, // faaIdent
                strings, // airportName
                booleans  // isMilitary
        ).as((city, stateAbbr, stateFull, country, icaoIdent, faaIdent, airportName, isMilitary) -> {
            UpstreamAirportData data = new UpstreamAirportData();
            data.setCity(city);
            data.setStateAbbr(stateAbbr);
            data.setStateFull(stateFull);
            data.setCountry(country);
            data.setIcaoIdent(icaoIdent);
            data.setFaaIdent(faaIdent);
            data.setAirportName(airportName);
            data.setIsMilitary(isMilitary);
            return data;
        });
    }
}
