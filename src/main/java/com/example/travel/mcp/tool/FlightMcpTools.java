package com.example.travel.mcp.tool;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import com.example.travel.mcp.service.FlightService;
import com.example.travel.mcp.service.McpToolGovernance;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class FlightMcpTools {

    private static final Logger log = LoggerFactory.getLogger(FlightMcpTools.class);

    private final FlightService flightService;
    private final McpToolGovernance governance;

    public FlightMcpTools(FlightService flightService, McpToolGovernance governance) {
        this.flightService = flightService;
        this.governance = governance;
    }

    @McpTool(name = "search_flights", description = "Search live flight fares/schedules by IATA airport code. Uses the enabled flight providers according to server configuration. AviationStack can be disabled with AVIATIONSTACK_ENABLED=false, in which case Ignav is used directly when enabled.")
    public SearchFlightsResponse searchFlights(
            @McpToolParam(description = "Three-letter origin IATA code, for example BLR", required = true) String origin,
            @McpToolParam(description = "Three-letter destination IATA code, for example BOM", required = true) String destination,
            @McpToolParam(description = "Departure date in yyyy-MM-dd format", required = false) String departureDate,
            @McpToolParam(description = "Return date in yyyy-MM-dd format, for trip context", required = false) String returnDate,
            @McpToolParam(description = "Number of passengers, from 1 to 9", required = false) Integer passengers,
            @McpToolParam(description = "Optional preferred provider, e.g. IGNAV or AVIATIONSTACK. Server may fall back to other healthy enabled providers.", required = false) String preferredProvider) {
        validateSearchRequest(origin, destination, departureDate, returnDate, passengers);
        governance.check("search_flights");
        long started = System.nanoTime();
        log.info("mcp.tool.request name=search_flights origin={} destination={} departureDate={} returnDate={} passengers={} preferredProvider={}",
            origin, destination, departureDate, returnDate, passengers, preferredProvider);
        log.info("mcp.tool.start name=search_flights origin={} destination={}", origin, destination);
        SearchFlightsResponse response = flightService.search(new SearchFlightsRequest(origin, destination, departureDate, returnDate, passengers, preferredProvider));
        log.info("mcp.tool.response name=search_flights response={}", response);
        log.info("mcp.tool.complete name=search_flights success={} results={} durationMs={}", response.success(), response.flights().size(), elapsedMs(started));
        return response;
    }

    private void validateSearchRequest(String origin, String destination,
                                       String departureDate, String returnDate,
                                       Integer passengers) {
        if (origin == null || !origin.trim().matches("(?i)[A-Z]{3}")) {
            throw new IllegalArgumentException("origin must be a three-letter IATA airport code");
        }
        if (destination == null || !destination.trim().matches("(?i)[A-Z]{3}")) {
            throw new IllegalArgumentException("destination must be a three-letter IATA airport code");
        }
        if (origin.trim().equalsIgnoreCase(destination.trim())) {
            throw new IllegalArgumentException("origin and destination must be different airports");
        }
        LocalDate departure = parseRequiredOrNull(departureDate, "departureDate");
        LocalDate returning = parseRequiredOrNull(returnDate, "returnDate");
        LocalDate today = LocalDate.now();
        if (departure != null && departure.isBefore(today)) {
            throw new IllegalArgumentException("departureDate must not be in the past");
        }
        if (returning != null && departure != null && returning.isBefore(departure)) {
            throw new IllegalArgumentException("returnDate must be on or after departureDate");
        }
        if (passengers != null && (passengers < 1 || passengers > 9)) {
            throw new IllegalArgumentException("passengers must be between 1 and 9");
        }
    }

    private LocalDate parseRequiredOrNull(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException invalidDate) {
            throw new IllegalArgumentException(fieldName + " must use yyyy-MM-dd format", invalidDate);
        }
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
