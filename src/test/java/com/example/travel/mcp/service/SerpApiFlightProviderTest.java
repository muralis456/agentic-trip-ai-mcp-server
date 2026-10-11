package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SerpApiFlightProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesOnlyMatchingRouteAndPreservesIndicativeFare() throws Exception {
        AtomicReference<String> requestUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            byte[] body = """
                    {
                      "search_metadata": {"status": "Success"},
                      "best_flights": [
                        {"flights": [
                          {"departure_airport": {"id": "BLR", "time": "2026-10-16 09:00"},
                           "arrival_airport": {"id": "BOM", "time": "2026-10-16 11:00"},
                           "airline": "IndiGo", "flight_number": "6E 201"}
                        ], "price": 6500},
                        {"flights": [
                          {"departure_airport": {"id": "DEL", "time": "2026-10-16 09:00"},
                           "arrival_airport": {"id": "BOM", "time": "2026-10-16 11:00"},
                           "airline": "Wrong Route", "flight_number": "XX 999"}
                        ], "price": 4000}
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();

        SerpApiFlightProvider provider = provider("test-key", true);
        var response = provider.search(new SearchFlightsRequest("BLR", "BOM", "2026-10-16", null, 1, ""));

        assertTrue(response.success(), response.message());
        assertEquals(1, response.flights().size(), "provider must reject an itinerary with a mismatched route");
        var flight = response.flights().getFirst();
        assertEquals("BLR", flight.origin());
        assertEquals("BOM", flight.destination());
        assertEquals("6E 201", flight.flightNumber());
        assertTrue(flight.notes().contains("price=6500"));
        assertTrue(flight.notes().contains("currency=INR"));
        assertTrue(flight.notes().contains("indicative"));
        assertTrue(requestUri.get().contains("engine=google_flights"));
        assertTrue(requestUri.get().contains("outbound_date=2026-10-16"));
        assertTrue(requestUri.get().contains("api_key=test-key"));
    }

    @Test
    void returnsConfigurationFailureWhenApiKeyIsMissing() {
        SerpApiFlightProvider provider = provider(" ", true);
        var response = provider.search(new SearchFlightsRequest("BLR", "BOM", "2026-10-16", null, 1, ""));
        assertFalse(response.success());
        assertEquals("SERPAPI_CONFIGURATION", response.errorCode());
    }

    @Test
    void rejectsRequestsWithoutDepartureDateBeforeCallingProvider() {
        SerpApiFlightProvider provider = provider("test-key", true);
        var response = provider.search(new SearchFlightsRequest("BLR", "BOM", " ", null, 1, ""));
        assertFalse(response.success());
        assertEquals("SERPAPI_DATE_REQUIRED", response.errorCode());
    }

    private SerpApiFlightProvider provider(String apiKey, boolean enabled) {
        String url = server == null
                ? "http://127.0.0.1:1/search"
                : "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
        return new SerpApiFlightProvider(
                RestClient.builder(), new ObjectMapper(), url, apiKey, "in", "en", "INR",
                enabled, 8, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
