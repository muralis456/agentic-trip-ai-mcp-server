package com.example.travel.mcp.dto;

/** Provider-neutral flight data returned to MCP clients. */
public record FlightResult(
        String flightNumber,
        String airline,
        String origin,
        String destination,
        String departureScheduled,
        String arrivalScheduled,
        String status,
        String notes) {
}
