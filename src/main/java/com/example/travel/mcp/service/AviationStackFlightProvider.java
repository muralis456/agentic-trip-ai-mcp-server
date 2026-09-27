package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(1)
public class AviationStackFlightProvider implements FlightProvider {
    private final AviationStackClient client;

    public AviationStackFlightProvider(AviationStackClient client) {
        this.client = client;
    }

    @Override public String name() { return "AviationStack"; }
    @Override public boolean enabled() { return client.enabled(); }
    @Override public SearchFlightsResponse search(SearchFlightsRequest request) { return client.search(request); }
}
