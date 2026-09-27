package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.FlightResult;
import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class IgnavClient {

    private static final Logger log = LoggerFactory.getLogger(IgnavClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String market;
    private final boolean enabled;

    public IgnavClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${travel.ignav.api-url:https://ignav.com/api}") String apiUrl,
            @Value("${travel.ignav.api-key:}") String apiKey,
            @Value("${travel.ignav.market:IN}") String market,
            @Value("${travel.ignav.enabled:true}") boolean enabled,
            @Value("${travel.ignav.connect-timeout:5s}") Duration connectTimeout,
            @Value("${travel.ignav.read-timeout:15s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.market = market == null || market.isBlank() ? "IN" : market.trim().toUpperCase(Locale.ROOT);
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public SearchFlightsResponse search(SearchFlightsRequest request) {
        if (!enabled) {
            return SearchFlightsResponse.failure("PROVIDER_CONFIGURATION", "Ignav is not configured or disabled.");
        }

        String origin = request.origin().trim().toUpperCase(Locale.ROOT);
        String destination = request.destination().trim().toUpperCase(Locale.ROOT);

        // Ignav requires a departure date even when the user intentionally left
        // the travel dates flexible. Use today only as the provider query date.
        // The upstream travel state remains date-flexible, so this fallback must
        // never be presented as the user's requested travel date. This allows
        // Ignav to act as a real fallback when AviationStack is rate-limited.
        String effectiveDepartureDate = request.departureDate();
        if (!validDate(effectiveDepartureDate)) {
            effectiveDepartureDate = LocalDate.now().toString();
            log.info("MCP search_flights provider=Ignav using providerFallbackDepartureDate={} because request departureDate is missing/invalid origin={} destination={}",
                    effectiveDepartureDate, origin, destination);
        }
        boolean roundTrip = validDate(request.returnDate());
        String endpoint = roundTrip ? "/fares/round-trip" : "/fares/one-way";
        long started = System.nanoTime();

        try {
            String payload = buildPayload(request, effectiveDepartureDate, origin, destination, roundTrip);
            log.info("MCP search_flights provider=Ignav mode={} origin={} destination={} departureDate={} returnDate={} passengers={} market={}",
                    roundTrip ? "round-trip" : "one-way", origin, destination,
                    effectiveDepartureDate, request.returnDate(), request.normalizedPassengers(), market);

            String body = executeWithTransientRetry(endpoint, payload, origin, destination);

            SearchFlightsResponse result = parse(body, origin, destination, request);
            log.info("MCP search_flights provider=Ignav completed success={} results={} durationMs={}",
                    result.success(), result.flights().size(), elapsedMs(started));
            return result;
        } catch (IgnavException exception) {
            log.warn("MCP search_flights provider=Ignav failed code={} message={} durationMs={}",
                    exception.code, exception.getMessage(), elapsedMs(started));
            return SearchFlightsResponse.failure(exception.code, exception.getMessage());
        } catch (RestClientException exception) {
            log.warn("MCP search_flights provider=Ignav unavailable error={} durationMs={}",
                    safeMessage(exception), elapsedMs(started));
            return SearchFlightsResponse.failure("PROVIDER_UNAVAILABLE", "Ignav is unavailable or timed out: " + safeMessage(exception));
        } catch (Exception exception) {
            log.warn("MCP search_flights provider=Ignav response could not be processed error={} durationMs={}",
                    safeMessage(exception), elapsedMs(started));
            return SearchFlightsResponse.failure("PROVIDER_RESPONSE", "Ignav returned an unreadable response: " + safeMessage(exception));
        }
    }


    private String executeWithTransientRetry(String endpoint, String payload, String origin, String destination) {
        final int maxAttempts = 2; // Ignav documents HTTP 424 as a transient upstream failure.
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            final int currentAttempt = attempt;
            try {
                return restClient.post()
                        .uri(apiUrl + endpoint)
                        .header("X-Api-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .body(payload)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (ignored, response) -> {
                            int status = response.getStatusCode().value();
                            String providerMessage = extractProviderMessage(response);
                            String providerCode = extractProviderCode(providerMessage);
                            String message = buildProviderErrorMessage(status, providerCode, providerMessage);
                            log.warn(
                                    "MCP search_flights provider=Ignav failed httpStatus={} providerCode={} attempt={} origin={} destination={}",
                                    status, providerCode, currentAttempt, origin, destination);
                            throw new IgnavException("IGNAV_HTTP_" + status, message);
                        })
                        .body(String.class);
            } catch (IgnavException exception) {
                if ("IGNAV_HTTP_424".equals(exception.code) && attempt < maxAttempts) {
                    log.warn("MCP search_flights provider=Ignav retrying transient HTTP 424 attempt={} nextAttempt={}",
                            attempt, attempt + 1);
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw exception;
            }
        }
        throw new IllegalStateException("Ignav request retry loop exhausted unexpectedly.");
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IgnavException("IGNAV_RETRY_INTERRUPTED", "Ignav retry was interrupted.");
        }
    }

    private String buildPayload(SearchFlightsRequest request, String departureDate, String origin, String destination, boolean roundTrip) {
        StringBuilder json = new StringBuilder("{");
        appendString(json, "origin", origin);
        appendString(json, "destination", destination);
        appendString(json, "departure_date", departureDate.trim());
        appendNumber(json, "adults", request.normalizedPassengers());
        appendString(json, "market", market);
        if (roundTrip) {
            appendString(json, "return_date", request.returnDate().trim());
        }
        json.append('}');
        return json.toString();
    }

    private void appendString(StringBuilder json, String key, String value) {
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(key).append("\":\"")
                .append(escapeJson(value))
                .append('"');
    }

    private void appendNumber(StringBuilder json, String key, int value) {
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(key).append("\":").append(value);
    }

    private SearchFlightsResponse parse(String body, String origin, String destination, SearchFlightsRequest request)
            throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode error = root.path("error");
        if (error.isObject()) {
            String code = nonBlank(error.path("code").asString(), "PROVIDER_ERROR");
            String message = nonBlank(error.path("message").asString(), "Ignav rejected the flight search request.");
            return SearchFlightsResponse.failure("IGNAV_" + code.toUpperCase(Locale.ROOT), message);
        }

        JsonNode itineraries = root.path("itineraries");
        if (!itineraries.isArray() || itineraries.isEmpty()) {
            return SearchFlightsResponse.success(List.of(), "Ignav returned no matching flights.");
        }

        List<FlightResult> flights = new ArrayList<>();
        for (JsonNode itinerary : itineraries) {
            if (flights.size() >= 5) {
                break;
            }
            JsonNode outbound = itinerary.path("outbound");
            JsonNode segments = outbound.path("segments");
            if (!segments.isArray() || segments.isEmpty()) {
                continue;
            }

            JsonNode first = segments.get(0);
            JsonNode last = segments.get(segments.size() - 1);
            String airline = nonBlank(outbound.path("carrier").asString(),
                    nonBlank(first.path("operating_carrier_name").asString(), "Unavailable"));
            String flightNumber = nonBlank(first.path("flight_number").asString(), "Unavailable");
            String departureAirport = nonBlank(first.path("departure_airport").asString(), origin);
            String arrivalAirport = nonBlank(last.path("arrival_airport").asString(), destination);
            String departure = nonBlank(first.path("departure_time_local").asString(),
                    first.path("departure_time_utc").asString());
            String arrival = nonBlank(last.path("arrival_time_local").asString(),
                    last.path("arrival_time_utc").asString());

            String notes = buildNotes(itinerary, outbound, segments.size(), request);
            flights.add(new FlightResult(
                    flightNumber,
                    airline,
                    departureAirport,
                    arrivalAirport,
                    departure,
                    arrival,
                    "scheduled",
                    notes));
        }

        if (flights.isEmpty()) {
            return SearchFlightsResponse.success(List.of(), "Ignav returned itineraries without usable flight segments.");
        }
        return SearchFlightsResponse.success(flights, "Flight search completed using Ignav fallback.");
    }

    private String buildNotes(JsonNode itinerary, JsonNode outbound, int segmentCount, SearchFlightsRequest request) {
        JsonNode price = itinerary.path("price");
        String amount = price.path("amount").asString();
        String currency = price.path("currency").asString();
        String priceText = amount.isBlank() ? "price unavailable" : amount + (currency.isBlank() ? "" : " " + currency);
        String id = itinerary.path("ignav_id").asString();
        boolean selfTransfer = itinerary.path("requires_self_transfer").asBoolean(false);
        StringBuilder notes = new StringBuilder("provider=Ignav; price=").append(priceText)
                .append("; segments=").append(segmentCount)
                .append("; selfTransfer=").append(selfTransfer);
        if (!id.isBlank()) {
            notes.append("; ignavId=").append(id);
        }
        if (validDate(request.returnDate())) {
            JsonNode inbound = itinerary.path("inbound");
            if (inbound.isObject()) {
                notes.append("; returnCarrier=").append(nonBlank(inbound.path("carrier").asString(), "Unavailable"));
            }
        }
        return notes.toString();
    }

    private String extractProviderMessage(org.springframework.http.client.ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return "No error details returned by Ignav.";
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                String message = nonBlank(error.path("message").asString(), "");
                if (!message.isBlank()) {
                    String code = error.path("code").asString();
                    return code.isBlank() ? message : code + ": " + message;
                }
            }
            return body.length() > 500 ? body.substring(0, 500) : body;
        } catch (Exception ignored) {
            return "Ignav returned HTTP " + safeStatusCode(response) + " without readable error details.";
        }
    }

    private int safeStatusCode(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return response.getStatusCode().value();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String extractProviderCode(String providerMessage) {
        if (providerMessage == null) {
            return "";
        }
        String lower = providerMessage.toLowerCase(Locale.ROOT);
        if (lower.contains("monthly_spend_limit") || lower.contains("rate limit") || lower.contains("too many requests")) {
            return "rate_limit_reached";
        }
        if (lower.contains("billing_required")) {
            return "billing_required";
        }
        return "";
    }

    private String buildProviderErrorMessage(int status, String providerCode, String providerMessage) {
        if (status == 429) {
            return "Ignav rate/monthly spending limit reached (HTTP 429). " + providerMessage
                    + " Do not retry until the limit is changed or the billing cycle resets.";
        }
        if (status == 402) {
            return "Ignav billing is required (HTTP 402). " + providerMessage;
        }
        if (status == 401 || status == 403) {
            return "Ignav authentication/access failed (HTTP " + status + "). Check IGNAV_API_KEY. " + providerMessage;
        }
        if (status == 400 || status == 422) {
            return "Ignav rejected the flight search request (HTTP " + status + "). " + providerMessage;
        }
        return "Ignav flight search failed (HTTP " + status + "). " + providerMessage;
    }

    private boolean validDate(String value) {
        try {
            LocalDate.parse(value == null ? "" : value.trim());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static final class IgnavException extends RuntimeException {
        private final String code;

        private IgnavException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
