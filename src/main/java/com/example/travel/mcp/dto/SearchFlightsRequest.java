package com.example.travel.mcp.dto;

/** Input accepted by the search_flights MCP tool. */
public record SearchFlightsRequest(
        String origin,
        String destination,
        String departureDate,
        String returnDate,
        Integer passengers,
        String preferredProvider) {

    public SearchFlightsRequest(String origin, String destination, String departureDate, String returnDate, Integer passengers) {
        this(origin, destination, departureDate, returnDate, passengers, "");
    }

    public int normalizedPassengers() {
        return passengers == null ? 1 : passengers;
    }
}
