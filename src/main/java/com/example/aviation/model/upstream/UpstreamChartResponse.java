package com.example.aviation.model.upstream;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpstreamChartResponse {

    @JsonProperty("airport_data")
    private UpstreamAirportData airportData;

    @JsonProperty("charts")
    private Object charts;
}
