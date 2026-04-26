package com.example.aviation.model.upstream;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpstreamAirportData {

    @JsonProperty("city")
    private String city;

    @JsonProperty("state_abbr")
    private String stateAbbr;

    @JsonProperty("state_full")
    private String stateFull;

    @JsonProperty("country")
    private String country;

    @JsonProperty("icao_ident")
    private String icaoIdent;

    @JsonProperty("faa_ident")
    private String faaIdent;

    @JsonProperty("airport_name")
    private String airportName;

    @JsonProperty("is_military")
    private Boolean isMilitary;
}
