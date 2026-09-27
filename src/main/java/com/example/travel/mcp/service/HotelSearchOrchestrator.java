package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.HotelResult;
import com.example.travel.mcp.dto.SearchHotelsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class HotelSearchOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(HotelSearchOrchestrator.class);
    private final List<HotelProvider> providers;
    private final HotelProviderHealth health;
    private final int maxResults;

    public HotelSearchOrchestrator(List<HotelProvider> providers,
                                   HotelProviderHealth health,
                                   @Value("${travel.hotels.max-results:8}") int maxResults) {
        this.providers = new ArrayList<>(providers);
        AnnotationAwareOrderComparator.sort(this.providers);
        this.health = health;
        this.maxResults = Math.max(1, maxResults);
    }

    public SearchHotelsResponse search(HotelSearchRequest request) {
        List<String> failures = new ArrayList<>();
        for (HotelProvider provider : providers) {
            if (!provider.enabled()) continue;
            if (!health.allowRequest()) {
                log.warn("mcp.hotel.provider.skipped provider={} reason=circuit-open", provider.name());
                break;
            }
            long started = System.nanoTime();
            try {
                SearchHotelsResponse response = provider.search(request);
                if (response != null && response.success() && response.hotels() != null && !response.hotels().isEmpty()) {
                    health.recordSuccess();
                    List<HotelResult> hotels = response.hotels().stream().limit(maxResults).toList();
                    log.info("mcp.hotel.orchestrator.success provider={} destination={} results={} durationMs={}",
                            provider.name(), request.destination(), hotels.size(), elapsedMs(started));
                    return SearchHotelsResponse.success(hotels,
                            response.message() + " provider=" + provider.name());
                }
                health.recordFailure();
                String message = response == null ? "empty provider response" : response.message();
                failures.add(provider.name() + ": " + message);
                log.warn("mcp.hotel.provider.unusable provider={} destination={} durationMs={} reason={}",
                        provider.name(), request.destination(), elapsedMs(started), message);
            } catch (Exception ex) {
                health.recordFailure();
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                failures.add(provider.name() + ": " + message);
                log.warn("mcp.hotel.provider.exception provider={} destination={} durationMs={} reason={}",
                        provider.name(), request.destination(), elapsedMs(started), message, ex);
            }
        }
        return SearchHotelsResponse.failure("HOTEL_PROVIDERS_UNAVAILABLE: " + String.join("; ", failures));
    }

    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
