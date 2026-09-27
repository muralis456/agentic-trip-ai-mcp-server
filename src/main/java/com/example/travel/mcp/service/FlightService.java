package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class FlightService {
    private final FlightSearchOrchestrator orchestrator;

    public FlightService(FlightSearchOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public SearchFlightsResponse search(SearchFlightsRequest request) {
        if (request == null) return SearchFlightsResponse.failure("INVALID_REQUEST", "Flight search input is required.");
        if (!iataCode(request.origin()) || !iataCode(request.destination())) {
            return SearchFlightsResponse.failure("INVALID_AIRPORT", "origin and destination must be three-letter IATA codes.");
        }
        String origin = request.origin().trim().toUpperCase();
        String destination = request.destination().trim().toUpperCase();
        if (origin.equals(destination)) {
            return SearchFlightsResponse.failure("INVALID_ROUTE", "origin and destination must be different airports.");
        }
        if (request.normalizedPassengers() < 1 || request.normalizedPassengers() > 9) {
            return SearchFlightsResponse.failure("INVALID_PASSENGERS", "passengers must be between 1 and 9.");
        }
        if (present(request.departureDate()) && !validDate(request.departureDate())) {
            return SearchFlightsResponse.failure("INVALID_DEPARTURE_DATE", "departureDate must use yyyy-MM-dd format.");
        }
        if (present(request.returnDate()) && !validDate(request.returnDate())) {
            return SearchFlightsResponse.failure("INVALID_RETURN_DATE", "returnDate must use yyyy-MM-dd format.");
        }
        if (present(request.departureDate())
                && LocalDate.parse(request.departureDate().trim()).isBefore(LocalDate.now())) {
            return SearchFlightsResponse.failure("INVALID_DEPARTURE_DATE_PAST",
                    "departureDate cannot be earlier than today at the origin airport.");
        }
        if (present(request.departureDate()) && present(request.returnDate())
                && LocalDate.parse(request.returnDate().trim()).isBefore(LocalDate.parse(request.departureDate().trim()))) {
            return SearchFlightsResponse.failure("INVALID_DATE_RANGE", "returnDate cannot be before departureDate.");
        }
        return orchestrator.search(new SearchFlightsRequest(origin, destination,
                trimToNull(request.departureDate()), trimToNull(request.returnDate()), request.normalizedPassengers()));
    }

    private boolean iataCode(String value) { return value != null && value.trim().matches("[A-Za-z]{3}"); }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    private boolean validDate(String value) { try { LocalDate.parse(value.trim()); return true; } catch (Exception ignored) { return false; } }
    private String trimToNull(String value) { return present(value) ? value.trim() : null; }
}
