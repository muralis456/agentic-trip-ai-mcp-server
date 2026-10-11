package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.FlightResult;
import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlightSearchOrchestratorTest {

    private static final String DATE = "2026-10-11";

    @Test
    void skipsAviationStackAndUsesSerpApiWhenIgnavReturnsWrongRoute() {
        AtomicInteger aviationCalls = new AtomicInteger();
        AtomicInteger ignavCalls = new AtomicInteger();
        AtomicInteger serpCalls = new AtomicInteger();

        FlightProvider aviation = provider("AviationStack", aviationCalls,
                SearchFlightsResponse.success(List.of(flight("JL999", "BLR", "BOM", DATE)), "bad provider"));
        FlightProvider ignav = provider("Ignav", ignavCalls,
                SearchFlightsResponse.success(List.of(flight("AI101", "DEL", "BOM", DATE)), "wrong route"));
        FlightProvider serp = provider("SerpApi Google Flights", serpCalls,
                SearchFlightsResponse.success(List.of(flight("6E201", "BLR", "BOM", DATE)), "fallback"));

        SearchFlightsResponse response = orchestrator(List.of(aviation, ignav, serp))
                .search(new SearchFlightsRequest("BLR", "BOM", DATE, null, 1, ""));

        assertTrue(response.success());
        assertEquals(0, aviationCalls.get(), "AviationStack must be bypassed");
        assertEquals(1, ignavCalls.get());
        assertEquals(1, serpCalls.get(), "SerpApi should be tried after unusable Ignav data");
        assertEquals(1, response.flights().size());
        assertEquals("6E201", response.flights().getFirst().flightNumber());
        assertTrue(response.message().contains("SerpApi Google Flights"));
    }

    @Test
    void skipsProviderResultsWithWrongDateAndContinuesFallback() {
        AtomicInteger ignavCalls = new AtomicInteger();
        AtomicInteger serpCalls = new AtomicInteger();
        FlightProvider ignav = provider("Ignav", ignavCalls,
                SearchFlightsResponse.success(List.of(flight("AI101", "BLR", "BOM", "2026-10-12")), "wrong date"));
        FlightProvider serp = provider("SerpApi Google Flights", serpCalls,
                SearchFlightsResponse.success(List.of(flight("6E201", "BLR", "BOM", DATE)), "fallback"));

        SearchFlightsResponse response = orchestrator(List.of(ignav, serp))
                .search(new SearchFlightsRequest("BLR", "BOM", DATE, null, 1, ""));

        assertTrue(response.success());
        assertEquals(1, ignavCalls.get());
        assertEquals(1, serpCalls.get());
        assertEquals("6E201", response.flights().getFirst().flightNumber());
    }

    @Test
    void continuesFallbackWhenIgnavHasScheduleButNoFare() {
        AtomicInteger ignavCalls = new AtomicInteger();
        AtomicInteger serpCalls = new AtomicInteger();
        FlightResult scheduleOnly = new FlightResult("AI101", "Air India", "BLR", "BOM",
                DATE + "T09:00", DATE + "T11:00", "scheduled", "provider=Ignav; fare unavailable");
        FlightResult priced = new FlightResult("6E201", "IndiGo", "BLR", "BOM",
                DATE + "T10:00", DATE + "T12:00", "scheduled",
                "provider=SerpApi Google Flights; price=6500; currency=INR");
        FlightProvider ignav = provider("Ignav", ignavCalls,
                SearchFlightsResponse.success(List.of(scheduleOnly), "schedule only"));
        FlightProvider serp = provider("SerpApi Google Flights", serpCalls,
                SearchFlightsResponse.success(List.of(priced), "fare available"));

        SearchFlightsResponse response = orchestrator(List.of(ignav, serp))
                .search(new SearchFlightsRequest("BLR", "BOM", DATE, null, 1, ""));

        assertTrue(response.success());
        assertEquals(1, ignavCalls.get());
        assertEquals(1, serpCalls.get(), "SerpApi should be tried to find prices");
        assertEquals("6E201", response.flights().getFirst().flightNumber());
        assertTrue(response.message().contains("SerpApi Google Flights"));
    }

    @Test
    void retainsValidScheduleOnlyResultsWhenNoAlternativeReturnsFare() {
        AtomicInteger ignavCalls = new AtomicInteger();
        AtomicInteger serpCalls = new AtomicInteger();
        FlightResult scheduleOnly = new FlightResult("AI101", "Air India", "BLR", "BOM",
                DATE + "T09:00", DATE + "T11:00", "scheduled", "provider=Ignav; fare unavailable");
        FlightProvider ignav = provider("Ignav", ignavCalls,
                SearchFlightsResponse.success(List.of(scheduleOnly), "schedule only"));
        FlightProvider serp = provider("SerpApi Google Flights", serpCalls,
                SearchFlightsResponse.failure("SERPAPI_CONFIGURATION", "not configured"));

        SearchFlightsResponse response = orchestrator(List.of(ignav, serp))
                .search(new SearchFlightsRequest("BLR", "BOM", DATE, null, 1, ""));

        assertTrue(response.success());
        assertEquals("AI101", response.flights().getFirst().flightNumber());
        assertTrue(response.message().contains("schedule-only"));
    }

    @Test
    void rejectsResultsWithoutARequestedDateWhenDateWasSpecified() {
        AtomicInteger calls = new AtomicInteger();
        FlightProvider noDate = provider("Ignav", calls,
                SearchFlightsResponse.success(List.of(flight("AI101", "BLR", "BOM", "")), "no date"));

        SearchFlightsResponse response = orchestrator(List.of(noDate))
                .search(new SearchFlightsRequest("BLR", "BOM", DATE, null, 1, ""));

        assertFalse(response.success());
        assertEquals(0, response.flights().size());
    }

    private FlightSearchOrchestrator orchestrator(List<FlightProvider> providers) {
        return new FlightSearchOrchestrator(providers, 3, Duration.ofSeconds(30), 10);
    }

    private FlightProvider provider(String name, AtomicInteger calls, SearchFlightsResponse response) {
        return new FlightProvider() {
            @Override public String name() { return name; }
            @Override public boolean enabled() { return true; }
            @Override public SearchFlightsResponse search(SearchFlightsRequest request) {
                calls.incrementAndGet();
                return response;
            }
        };
    }

    private FlightResult flight(String number, String origin, String destination, String departure) {
        return new FlightResult(number, "Test Airline", origin, destination,
                departure, departure, "scheduled", "provider=test");
    }
}
