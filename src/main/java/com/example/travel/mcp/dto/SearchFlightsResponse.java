package com.example.travel.mcp.dto;

import java.util.List;

/** Stable MCP response that hides AviationStack-specific response details. */
public record SearchFlightsResponse(
        boolean success,
        List<FlightResult> flights,
        String errorCode,
        String message) {

    public static SearchFlightsResponse success(List<FlightResult> flights, String message) {
        return new SearchFlightsResponse(true, flights == null ? List.of() : List.copyOf(flights), "", message);
    }

    public static SearchFlightsResponse failure(String errorCode, String message) {
        return new SearchFlightsResponse(false, List.of(), errorCode, message);
    }
}
