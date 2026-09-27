package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.HotelResult;
import com.example.travel.mcp.dto.SearchHotelsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

@Component
@Order(1)
public class SerpApiHotelProvider implements HotelProvider {
    private static final Logger log = LoggerFactory.getLogger(SerpApiHotelProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String country;
    private final String language;
    private final String currency;
    private final boolean enabled;
    private final int maxResults;

    public SerpApiHotelProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${travel.serpapi.api-url:https://serpapi.com/search}") String apiUrl,
            @Value("${travel.serpapi.api-key:}") String apiKey,
            @Value("${travel.serpapi.gl:in}") String country,
            @Value("${travel.serpapi.hl:en}") String language,
            @Value("${travel.serpapi.currency:INR}") String currency,
            @Value("${travel.serpapi.enabled:true}") boolean enabled,
            @Value("${travel.serpapi.max-results:8}") int maxResults,
            @Value("${travel.serpapi.connect-timeout:5s}") java.time.Duration connectTimeout,
            @Value("${travel.serpapi.read-timeout:15s}") java.time.Duration readTimeout) {
        this.restClient = builder
                .requestFactory(HotelHttpRequestFactory.create(connectTimeout, readTimeout))
                .build();
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.country = country;
        this.language = language;
        this.currency = currency;
        this.enabled = enabled;
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public String name() { return "SerpApi Google Hotels"; }

    @Override
    public boolean enabled() { return enabled && apiKey != null && !apiKey.isBlank(); }

    @Override
    public SearchHotelsResponse search(HotelSearchRequest request) {
        if (!enabled()) return SearchHotelsResponse.failure("SerpApi hotel provider is not configured.");
        if (request.checkIn() == null || request.checkOut() == null || !request.checkOut().isAfter(request.checkIn())) {
            return SearchHotelsResponse.failure("SerpApi requires a valid hotel check-in and check-out date.");
        }
        try {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("engine", "google_hotels")
                    .queryParam("q", request.destination())
                    .queryParam("gl", country)
                    .queryParam("hl", language)
                    .queryParam("currency", currency)
                    .queryParam("check_in_date", request.checkIn())
                    .queryParam("check_out_date", request.checkOut())
                    .queryParam("adults", Math.max(1, request.adults()))
                    .queryParam("children", Math.max(0, request.children()))
                    .queryParam("api_key", apiKey)
                    .queryParam("output", "json");

            if (request.cheaper()) uri.queryParam("sort_by", "3");
            if (request.maxPricePerNight() != null && request.maxPricePerNight().signum() > 0) {
                uri.queryParam("max_price", request.maxPricePerNight().setScale(0, java.math.RoundingMode.DOWN));
            }

            log.info("mcp.hotel.provider.start provider=serpapi destination={} checkIn={} checkOut={} adults={} children={} cheaper={} maxPrice={}",
                    request.destination(), request.checkIn(), request.checkOut(), request.adults(), request.children(),
                    request.cheaper(), request.maxPricePerNight());

            String body = restClient.get().uri(uri.build().toUri()).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("search_metadata").path("status").asString("");
            String error = root.path("error").asString("");
            if (!error.isBlank() || (!status.isBlank() && !"Success".equalsIgnoreCase(status))) {
                String reason = error.isBlank() ? "SerpApi returned status " + status : error;
                log.warn("mcp.hotel.provider.failure provider=serpapi destination={} reason={}", request.destination(), abbreviate(reason));
                return SearchHotelsResponse.failure("SERPAPI_PROVIDER_ERROR: " + reason);
            }

            List<HotelResult> hotels = mapProperties(root.path("properties"), request.destination());
            if (hotels.isEmpty()) {
                return SearchHotelsResponse.failure("SERPAPI_NO_HOTELS: SerpApi returned no usable hotel properties.");
            }
            return SearchHotelsResponse.success(hotels, "Hotel search completed using SerpApi Google Hotels.");
        } catch (RestClientResponseException ex) {
            String provider = ex.getResponseBodyAsString();
            String message = extractError(provider);
            String reason = "SerpApi hotel search failed (HTTP " + ex.getStatusCode().value() + ")"
                    + (message.isBlank() ? "" : ": " + message);
            log.warn("mcp.hotel.provider.failure provider=serpapi destination={} status={} reason={}",
                    request.destination(), ex.getStatusCode().value(), abbreviate(reason));
            return SearchHotelsResponse.failure("SERPAPI_HTTP_" + ex.getStatusCode().value() + ": " + reason);
        } catch (Exception ex) {
            log.warn("mcp.hotel.provider.failure provider=serpapi destination={} exceptionType={} reason={}",
                    request.destination(), ex.getClass().getSimpleName(), abbreviate(ex.getMessage()));
            return SearchHotelsResponse.failure("SERPAPI_PROVIDER_ERROR: " + safe(ex.getMessage(), ex.getClass().getSimpleName()));
        }
    }

    private List<HotelResult> mapProperties(JsonNode properties, String destination) {
        if (!properties.isArray()) return List.of();
        List<HotelResult> results = new ArrayList<>();
        for (JsonNode p : properties) {
            if (!"hotel".equalsIgnoreCase(p.path("type").asString("hotel"))) continue;
            String name = p.path("name").asString("").trim();
            if (name.isBlank()) continue;
            JsonNode rate = p.path("rate_per_night");
            String nightly = firstNonBlank(rate.path("lowest").asString(""), rate.path("before_taxes_fees").asString(""));
            String total = p.path("total_rate").path("lowest").asString("");
            String address = firstNonBlank(p.path("address").asString(""), destination);
            String rating = p.path("overall_rating").isNumber() ? p.path("overall_rating").asString() : "";
            String hotelClass = firstNonBlank(p.path("hotel_class").asString(""),
                    p.path("extracted_hotel_class").isNumber() ? p.path("extracted_hotel_class").asString() + "-star hotel" : "");
            String imageUrl = firstImage(p.path("images"));
            String bookingUrl = firstNonBlank(p.path("link").asString(""), p.path("serpapi_property_details_link").asString(""));
            int reviews = p.path("reviews").isNumber() ? p.path("reviews").asInt() : 0;
            String deal = firstNonBlank(p.path("deal_description").asString(""), p.path("deal").asString(""));
            String amenities = joinArray(p.path("amenities"), 8);
            String nearby = nearbySummary(p.path("nearby_places"));
            boolean freeCancellation = p.path("free_cancellation").asBoolean(false);
            String suitable = requestFit(p);
            String notes = buildNotes(p, hotelClass, reviews, total, deal, amenities, nearby, freeCancellation);
            results.add(new HotelResult(name, address, nightly, rating, suitable, notes,
                    imageUrl, bookingUrl, amenities, hotelClass, reviews, total, currency,
                    deal, freeCancellation, p.path("property_token").asString(""), "SerpApi"));
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    private String buildNotes(JsonNode p, String hotelClass, int reviews, String total, String deal,
                              String amenities, String nearby, boolean freeCancellation) {
        StringJoiner j = new StringJoiner(" · ");
        if (!hotelClass.isBlank()) j.add(hotelClass);
        if (reviews > 0) j.add(String.format("%,d reviews", reviews));
        if (!total.isBlank()) j.add("total " + total);
        if (!deal.isBlank()) j.add(deal);
        if (freeCancellation) j.add("Free cancellation");
        if (!nearby.isBlank()) j.add("Near " + nearby);
        return j.toString();
    }

    private String requestFit(JsonNode p) {
        List<String> a = new ArrayList<>();
        if (p.path("free_cancellation").asBoolean(false)) a.add("Flexible cancellation");
        if (p.path("eco_certified").asBoolean(false)) a.add("Eco-certified");
        return String.join(" · ", a);
    }

    private String nearbySummary(JsonNode nearby) {
        if (!nearby.isArray() || nearby.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (JsonNode n : nearby) {
            String name = n.path("name").asString("");
            if (!name.isBlank()) values.add(name);
            if (values.size() >= 2) break;
        }
        return String.join(", ", values);
    }

    private String joinArray(JsonNode array, int max) {
        if (!array.isArray()) return "";
        List<String> values = new ArrayList<>();
        for (JsonNode n : array) {
            String value = n.asString("");
            if (!value.isBlank()) values.add(value);
            if (values.size() >= max) break;
        }
        return String.join(", ", values);
    }

    private String firstImage(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) return "";
        JsonNode first = images.get(0);
        return firstNonBlank(first.path("thumbnail").asString(""), first.path("original_image").asString(""));
    }

    private String extractError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return firstNonBlank(root.path("error").asString(""), root.path("message").asString(""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String firstNonBlank(String a, String b) { return a != null && !a.isBlank() ? a : (b == null ? "" : b); }
    private String safe(String a, String fallback) { return a == null || a.isBlank() ? fallback : a; }
    private String abbreviate(String value) {
        if (value == null) return "";
        String s = value.replace('\n', ' ').trim();
        return s.length() <= 180 ? s : s.substring(0, 180) + "...";
    }
}
