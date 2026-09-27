package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(2)
public class IgnavFlightProvider implements FlightProvider {
    private final IgnavClient client;

    public IgnavFlightProvider(IgnavClient client) {
        this.client = client;
    }

    @Override public String name() { return "Ignav"; }
    @Override public boolean enabled() { return client.enabled(); }
    @Override public SearchFlightsResponse search(SearchFlightsRequest request) { return client.search(request); }
}
