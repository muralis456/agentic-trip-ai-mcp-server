package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.FlightResult;
import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FlightSearchOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(FlightSearchOrchestrator.class);
    private static final Pattern PRICE = Pattern.compile("(?:^|;\\s*)price=([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]{3})?");

    private final List<FlightProvider> providers;
    private final Map<String, FlightProviderHealth> health = new LinkedHashMap<>();
    private final int failureThreshold;
    private final long cooldownMillis;
    private final int maxResults;

    public FlightSearchOrchestrator(
            List<FlightProvider> providers,
            @Value("${travel.flights.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${travel.flights.circuit-breaker.cooldown:60s}") java.time.Duration cooldown,
            @Value("${travel.flights.max-results:10}") int maxResults) {
        this.providers = List.copyOf(providers);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(1000L, cooldown.toMillis());
        this.maxResults = Math.max(1, maxResults);
        for (FlightProvider provider : this.providers) {
            health.put(provider.name(), new FlightProviderHealth(this.failureThreshold, this.cooldownMillis));
        }
    }

    public SearchFlightsResponse search(SearchFlightsRequest request) {
        List<FlightResult> collected = new ArrayList<>();
        List<String> providersUsed = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        for (FlightProvider provider : providers) {
            if (!provider.enabled()) {
                diagnostics.add(provider.name() + " disabled/unconfigured");
                log.info("flight.provider.skip provider={} reason=disabled-or-unconfigured", provider.name());
                continue;
            }
            FlightProviderHealth state = health.get(provider.name());
            if (!state.allowRequest()) {
                diagnostics.add(provider.name() + " circuit-open");
                log.warn("flight.provider.skip provider={} reason=circuit-open failures={}", provider.name(), state.failures());
                continue;
            }

            long started = System.nanoTime();
            try {
                log.info("flight.provider.start provider={} origin={} destination={}", provider.name(), request.origin(), request.destination());
                SearchFlightsResponse response = provider.search(request);
                if (response == null) {
                    state.failure(true);
                    diagnostics.add(provider.name() + " returned null response");
                    continue;
                }

                if (response.success() && !response.flights().isEmpty()) {
                    state.success();
                    providersUsed.add(provider.name());
                    collected.addAll(response.flights());
                    diagnostics.add(provider.name() + " results=" + response.flights().size());
                    log.info("flight.provider.success provider={} results={} durationMs={}", provider.name(), response.flights().size(), elapsedMs(started));

                    // Default strategy is resilient fallback, not a fan-out call. Stop after usable results.
                    break;
                }

                boolean countFailure = isTransientFailure(response);
                state.failure(countFailure);
                diagnostics.add(provider.name() + " " + (response.success() ? "empty" : response.errorCode()));
                log.warn("flight.provider.no-usable-results provider={} success={} code={} results={} circuitFailures={} durationMs={}",
                        provider.name(), response.success(), response.errorCode(), response.flights().size(), state.failures(), elapsedMs(started));
            } catch (RuntimeException ex) {
                state.failure(true);
                diagnostics.add(provider.name() + " exception=" + safeMessage(ex));
                log.error("flight.provider.exception provider={} durationMs={} message={}", provider.name(), elapsedMs(started), safeMessage(ex), ex);
            }
        }

        if (!collected.isEmpty()) {
            List<FlightResult> normalized = normalize(collected);
            String providerText = String.join(" + ", providersUsed);
            return SearchFlightsResponse.success(normalized,
                    "Flight search completed using " + providerText + ". " + String.join("; ", diagnostics));
        }

        String message = diagnostics.isEmpty() ? "No enabled flight provider is available." : String.join("; ", diagnostics);
        return SearchFlightsResponse.failure("FLIGHT_PROVIDERS_UNAVAILABLE", message);
    }

    private boolean isTransientFailure(SearchFlightsResponse response) {
        if (response.success()) return false;
        String code = response.errorCode() == null ? "" : response.errorCode().toUpperCase(Locale.ROOT);
        // Rate limits, auth failures and provider outages should open the circuit so we stop
        // hammering an unhealthy provider. Bad requests are caller errors and should not.
        if (code.contains("400") || code.contains("422") || code.contains("CONFIGURATION")
                || code.contains("INVALID_REQUEST") || code.contains("INVALID_AIRPORT")) return false;
        return true;
    }

    private List<FlightResult> normalize(List<FlightResult> flights) {
        Map<String, FlightResult> unique = new LinkedHashMap<>();
        for (FlightResult flight : flights) {
            if (flight == null || blank(flight.flightNumber()) || blank(flight.origin()) || blank(flight.destination())) continue;
            unique.putIfAbsent(dedupKey(flight), flight);
        }
        List<FlightResult> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingDouble(this::priceOrInfinity)
                .thenComparing(f -> nullToEmpty(f.airline()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(f -> nullToEmpty(f.departureScheduled())));
        return result.size() <= maxResults ? result : new ArrayList<>(result.subList(0, maxResults));
    }

    private String dedupKey(FlightResult f) {
        return (nullToEmpty(f.airline()) + "|" + nullToEmpty(f.flightNumber()) + "|"
                + nullToEmpty(f.origin()) + "|" + nullToEmpty(f.destination()) + "|"
                + nullToEmpty(f.departureScheduled())).toUpperCase(Locale.ROOT);
    }

    private double priceOrInfinity(FlightResult f) {
        if (f.notes() == null) return Double.POSITIVE_INFINITY;
        Matcher m = PRICE.matcher(f.notes());
        if (!m.find()) return Double.POSITIVE_INFINITY;
        try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { return Double.POSITIVE_INFINITY; }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String nullToEmpty(String value) { return value == null ? "" : value.trim(); }
    private String safeMessage(Exception e) { return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage(); }
    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000L; }
}
