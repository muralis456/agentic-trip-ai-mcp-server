package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.FlightResult;
import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Flights fallback through SerpApi. This is deliberately ordered after
 * Ignav: AviationStack is currently excluded by FlightSearchOrchestrator.
 */
@Component
@Order(3)
public class SerpApiFlightProvider implements FlightProvider {
    private static final Logger log = LoggerFactory.getLogger(SerpApiFlightProvider.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String apiUrl;
    private final String apiKey;
    private final String gl;
    private final String hl;
    private final String currency;
    private final boolean configuredEnabled;
    private final int maxResults;

    public SerpApiFlightProvider(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${travel.serpapi.api-url:https://serpapi.com/search}") String apiUrl,
            @Value("${travel.serpapi.api-key:}") String apiKey,
            @Value("${travel.serpapi.gl:in}") String gl,
            @Value("${travel.serpapi.hl:en}") String hl,
            @Value("${travel.serpapi.currency:INR}") String currency,
            @Value("${travel.serpapi.enabled:true}") boolean enabled,
            @Value("${travel.serpapi.max-results:8}") int maxResults,
            @Value("${travel.serpapi.connect-timeout:5s}") Duration connectTimeout,
            @Value("${travel.serpapi.read-timeout:15s}") Duration readTimeout) {
        this.client = builder.requestFactory(HotelHttpRequestFactory.create(connectTimeout, readTimeout)).build();
        this.mapper = mapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.gl = gl;
        this.hl = hl;
        this.currency = currency;
        this.configuredEnabled = enabled;
        this.maxResults = Math.max(1, maxResults);
    }

    @Override public String name() { return "SerpApi Google Flights"; }

    @Override public boolean enabled() {
        return configuredEnabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public SearchFlightsResponse search(SearchFlightsRequest request) {
        if (!enabled()) return SearchFlightsResponse.failure("SERPAPI_CONFIGURATION", "SerpApi Google Flights is not configured.");
        if (blank(request.departureDate())) {
            return SearchFlightsResponse.failure("SERPAPI_DATE_REQUIRED", "SerpApi Google Flights requires a concrete departure date.");
        }
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("engine", "google_flights")
                    .queryParam("departure_id", request.origin())
                    .queryParam("arrival_id", request.destination())
                    .queryParam("outbound_date", request.departureDate())
                    .queryParam("adults", Math.max(1, request.normalizedPassengers()))
                    .queryParam("currency", currency)
                    .queryParam("gl", gl)
                    .queryParam("hl", hl)
                    .queryParam("api_key", apiKey)
                    .queryParam("deep_search", "false")
                    .queryParam("output", "json");
            boolean roundTrip = !blank(request.returnDate());
            if (roundTrip) {
                uri.queryParam("type", "1").queryParam("return_date", request.returnDate());
            } else {
                uri.queryParam("type", "2");
            }
            log.info("mcp.flight.provider.start provider=serpapi origin={} destination={} departureDate={} roundTrip={}",
                    request.origin(), request.destination(), request.departureDate(), roundTrip);
            String body = client.get().uri(uri.build().toUri()).retrieve().body(String.class);
            JsonNode root = mapper.readTree(body);
            String error = root.path("error").asString("");
            String status = root.path("search_metadata").path("status").asString("");
            if (!error.isBlank() || (!status.isBlank() && !"Success".equalsIgnoreCase(status))) {
                String reason = !error.isBlank() ? error : "SerpApi status " + status;
                return SearchFlightsResponse.failure("SERPAPI_PROVIDER_ERROR", reason);
            }

            List<FlightResult> results = new ArrayList<>();
            appendFlights(root.path("best_flights"), results, request);
            appendFlights(root.path("other_flights"), results, request);
            if (results.isEmpty()) return SearchFlightsResponse.success(List.of(), "SerpApi returned no usable flight segments.");
            return SearchFlightsResponse.success(results, "Flight search completed using SerpApi Google Flights.");
        } catch (RestClientResponseException ex) {
            log.warn("mcp.flight.provider.failure provider=serpapi status={}", ex.getStatusCode().value());
            return SearchFlightsResponse.failure("SERPAPI_HTTP_" + ex.getStatusCode().value(),
                    "SerpApi Google Flights request failed with HTTP " + ex.getStatusCode().value() + ".");
        } catch (Exception ex) {
            log.warn("mcp.flight.provider.failure provider=serpapi exceptionType={} message={}",
                    ex.getClass().getSimpleName(), safe(ex.getMessage()));
            return SearchFlightsResponse.failure("SERPAPI_PROVIDER_ERROR", "SerpApi flight search failed: " + safe(ex.getMessage()));
        }
    }

    private void appendFlights(JsonNode itineraries, List<FlightResult> results, SearchFlightsRequest request) {
        if (!itineraries.isArray()) return;
        for (JsonNode itinerary : itineraries) {
            if (results.size() >= maxResults) return;
            JsonNode flights = itinerary.path("flights");
            if (!flights.isArray() || flights.isEmpty()) continue;
            JsonNode first = flights.get(0);
            JsonNode last = flights.get(flights.size() - 1);
            String departureAirport = first.path("departure_airport").path("id").asString("");
            String arrivalAirport = last.path("arrival_airport").path("id").asString("");
            // Never invent an itinerary's route when upstream data omits route codes.
            if (blank(departureAirport) || blank(arrivalAirport)
                    || !request.origin().equalsIgnoreCase(departureAirport)
                    || !request.destination().equalsIgnoreCase(arrivalAirport)) continue;
            String airline = first.path("airline").asString("");
            String flightNumber = first.path("flight_number").asString("");
            String departure = first.path("departure_airport").path("time").asString("");
            String arrival = last.path("arrival_airport").path("time").asString("");
            String notes = "SerpApi Google Flights schedule; fare shown by provider: "
                    + itinerary.path("price").asString("unavailable");
            results.add(new FlightResult(flightNumber, airline, departureAirport, arrivalAirport,
                    departure, arrival, "scheduled", notes));
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null || value.isBlank() ? "unknown error" : value.replaceAll("[\\r\\n\\t]+", " ").trim(); }
}
