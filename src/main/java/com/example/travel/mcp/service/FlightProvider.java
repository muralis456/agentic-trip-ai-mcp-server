package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;

/** Provider abstraction used by the flight orchestrator. */
public interface FlightProvider {
    String name();
    boolean enabled();
    SearchFlightsResponse search(SearchFlightsRequest request);
}
