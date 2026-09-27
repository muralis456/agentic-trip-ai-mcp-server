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
            @McpToolParam(description = "Number of passengers, from 1 to 9", required = false) Integer passengers) {
        governance.check("search_flights");
        long started = System.nanoTime();
        log.info("mcp.tool.request name=search_flights origin={} destination={} departureDate={} returnDate={} passengers={}",
            origin, destination, departureDate, returnDate, passengers);
        log.info("mcp.tool.start name=search_flights origin={} destination={}", origin, destination);
        SearchFlightsResponse response = flightService.search(new SearchFlightsRequest(origin, destination, departureDate, returnDate, passengers));
        log.info("mcp.tool.response name=search_flights response={}", response);
        log.info("mcp.tool.complete name=search_flights success={} results={} durationMs={}", response.success(), response.flights().size(), elapsedMs(started));
        return response;
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
