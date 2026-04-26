package com.example.aviation.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AirportInfo {
    private final String icaoCode;
    private final String faaIdent;
    private final String airportName;
    private final String city;
    private final String stateAbbr;
    private final String stateFull;
    private final String country;
    private final Boolean isMilitary;
}
