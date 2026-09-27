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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AviationStackClient {

    private static final Logger log = LoggerFactory.getLogger(AviationStackClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final boolean includeFlightDate;
    private final boolean enabled;

    public AviationStackClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${travel.aviation.api-url}") String apiUrl,
            @Value("${travel.aviation.api-key}") String apiKey,
            @Value("${travel.aviation.include-flight-date:false}") boolean includeFlightDate,
            @Value("${travel.aviation.enabled:true}") boolean enabled,
            @Value("${travel.aviation.connect-timeout:5s}") Duration connectTimeout,
            @Value("${travel.aviation.read-timeout:15s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.includeFlightDate = includeFlightDate;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public SearchFlightsResponse search(SearchFlightsRequest request) {
        if (!enabled) {
            return SearchFlightsResponse.failure("PROVIDER_DISABLED", "AviationStack is disabled by configuration.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return SearchFlightsResponse.failure("PROVIDER_CONFIGURATION", "AviationStack is not configured.");
        }

        String origin = request.origin().trim().toUpperCase(Locale.ROOT);
        String destination = request.destination().trim().toUpperCase(Locale.ROOT);
        String url = requestUrl(origin, destination, request.departureDate());
        long started = System.nanoTime();
        try {
            log.info("MCP search_flights provider=AviationStack origin={} destination={}", origin, destination);
            String body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (ignored, response) -> {
                        int status = response.getStatusCode().value();
                        String providerMessage = extractProviderMessage(response);
                        String providerCode = extractProviderCode(providerMessage);
                        String message = buildProviderErrorMessage(status, providerCode, providerMessage);
                        log.error(
                                "MCP search_flights provider=AviationStack failed httpStatus={} providerCode={} providerMessage={} origin={} destination={}",
                                status, providerCode, providerMessage, origin, destination);
                        throw new AviationStackException("PROVIDER_HTTP_" + status, message);
                    })
                    .body(String.class);
            SearchFlightsResponse result = parse(body, origin, destination, request);
            log.info("MCP search_flights completed success={} results={} durationMs={}",
                    result.success(), result.flights().size(), elapsedMs(started));
            return result;
        } catch (AviationStackException exception) {
            log.warn("MCP search_flights failed code={} message={} durationMs={}",
                    exception.code, exception.getMessage(), elapsedMs(started));
            return SearchFlightsResponse.failure(exception.code, exception.getMessage());
        } catch (RestClientException exception) {
            log.warn("MCP search_flights provider unavailable durationMs={}", elapsedMs(started));
            return SearchFlightsResponse.failure("PROVIDER_UNAVAILABLE", "AviationStack is unavailable or timed out.");
        } catch (Exception exception) {
            log.warn("MCP search_flights provider response could not be processed durationMs={}", elapsedMs(started));
            return SearchFlightsResponse.failure("PROVIDER_RESPONSE", "AviationStack returned an unreadable response.");
        }
    }

    private String requestUrl(String origin, String destination, String departureDate) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(apiUrl + "/flights")
                .queryParam("access_key", apiKey)
                .queryParam("dep_iata", origin)
                .queryParam("arr_iata", destination);
        if (includeFlightDate && validDate(departureDate)) {
            builder.queryParam("flight_date", departureDate.trim());
        }
        return builder.build().toUriString();
    }

    private String extractProviderMessage(org.springframework.http.client.ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return "No error details returned by AviationStack.";
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                String message = nonBlank(error.path("message").asString(), "");
                if (!message.isBlank()) {
                    return message;
                }
            }
            return body.length() > 500 ? body.substring(0, 500) : body;
        } catch (Exception ignored) {
            return "AviationStack returned HTTP " + safeStatusCode(response) + " without readable error details.";
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
        if (lower.contains("rate") || lower.contains("limit") || lower.contains("quota")) {
            return "rate_limit_reached";
        }
        return "";
    }

    private String buildProviderErrorMessage(int status, String providerCode, String providerMessage) {
        if (status == 429 || "rate_limit_reached".equals(providerCode)) {
            return "AviationStack rate limit/quota reached (HTTP 429). "
                    + providerMessage
                    + " Flight search will not be retried automatically.";
        }
        if (status == 401) {
            return "AviationStack authentication failed (HTTP 401). Check AVIATIONSTACK_API_KEY.";
        }
        if (status == 403) {
            return "AviationStack access denied (HTTP 403). Check the subscription plan and endpoint permissions. "
                    + providerMessage;
        }
        if (status == 400 || status == 422) {
            return "AviationStack rejected the flight search request (HTTP " + status + "). " + providerMessage;
        }
        return "AviationStack flight search failed (HTTP " + status + "). " + providerMessage;
    }

    private SearchFlightsResponse parse(String body, String origin, String destination, SearchFlightsRequest request)
            throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").isObject()) {
            return SearchFlightsResponse.failure("PROVIDER_ERROR",
                    nonBlank(root.path("error").path("message").asString(), "AviationStack rejected the request."));
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return SearchFlightsResponse.success(List.of(), "No flights were returned.");
        }

        List<FlightResult> flights = new ArrayList<>();
        for (JsonNode flight : data) {
            if (flights.size() == 5) {
                break;
            }
            flights.add(new FlightResult(
                    text(flight.path("flight"), "iata", "Unavailable"),
                    text(flight.path("airline"), "name", "Unavailable"),
                    text(flight.path("departure"), "iata", origin),
                    text(flight.path("arrival"), "iata", destination),
                    text(flight.path("departure"), "scheduled", ""),
                    text(flight.path("arrival"), "scheduled", ""),
                    text(flight, "flight_status", "unknown"),
                    tripContext(request)));
        }
        return SearchFlightsResponse.success(flights, "Flight search completed.");
    }

    private String tripContext(SearchFlightsRequest request) {
        if (validDate(request.departureDate())) {
            return "live schedule; trip departure " + request.departureDate().trim();
        }
        return "live schedule";
    }

    private boolean validDate(String value) {
        try {
            LocalDate.parse(value == null ? "" : value.trim());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        return nonBlank(node.path(field).asString(), fallback);
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static final class AviationStackException extends RuntimeException {
        private final String code;

        private AviationStackException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
