package com.example.travel.mcp.dto;

public record AirportResult(boolean success, String iata, String city, String country, String message) {
}
