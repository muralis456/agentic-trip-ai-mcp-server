package com.example.travel.mcp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JettovaItineraryProvider {

    private static final Logger log = LoggerFactory.getLogger(JettovaItineraryProvider.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final boolean enabled;

    public JettovaItineraryProvider(RestClient.Builder builder,
                                    ObjectMapper objectMapper,
                                    @Value("${travel.jettova.api-url:https://www.jettova.com/api/v1}") String apiUrl,
                                    @Value("${travel.jettova.api-key:}") String apiKey,
                                    @Value("${travel.jettova.enabled:true}") boolean enabled,
                                    @Value("${travel.jettova.connect-timeout:5s}") Duration connectTimeout,
                                    @Value("${travel.jettova.read-timeout:60s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restClient = builder.requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.enabled = enabled;
    }

    public JettovaItineraryResponse generate(String destination,
                                             int days,
                                             LocalDate startDate,
                                             LocalDate endDate,
                                             int adults,
                                             String travelStyle,
                                             boolean foodExperience,
                                             boolean localExperience,
                                             boolean familyFriendly,
                                             String budgetLabel) {
        if (!enabled) {
            return JettovaItineraryResponse.failure("Jettova itinerary provider is disabled.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return JettovaItineraryResponse.failure("Jettova API key is not configured.");
        }
        if (destination == null || destination.isBlank()) {
            return JettovaItineraryResponse.failure("Destination is required for itinerary generation.");
        }

        int safeDays = Math.max(1, Math.min(14, days));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("destination", destination.trim());
        payload.put("days", safeDays);
        payload.put("pace", mapPace(travelStyle));
        payload.put("vibes", buildVibes(travelStyle, foodExperience, localExperience, familyFriendly));
        if (startDate != null && endDate != null) {
            payload.put("dates", Map.of("start", startDate.toString(), "end", endDate.toString()));
        }
        payload.put("adults", Math.max(1, adults));

        String idempotencyKey = "agentic-trip-ai-itinerary-" + UUID.randomUUID();
        long started = System.nanoTime();
        try {
            log.info("jettova.itinerary.start destination={} days={} startDate={} endDate={} adults={} style={} keyConfigured=true",
                    destination, safeDays, startDate, endDate, adults, travelStyle);

            String response = restClient.post()
                    .uri(apiUrl + "/itinerary")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            List<Map<String, Object>> mappedDays = mapDays(root);
            String summary = firstText(root, "summary", "overview", "description");
            if (summary.isBlank()) {
                summary = "Jettova generated a day-by-day itinerary for " + destination + ".";
            }
            if (mappedDays.isEmpty()) {
                return JettovaItineraryResponse.failure("Jettova returned no itinerary days.");
            }
            log.info("jettova.itinerary.complete destination={} days={} durationMs={}",
                    destination, mappedDays.size(), elapsedMs(started));
            return new JettovaItineraryResponse(true, "Jettova", destination, summary, mappedDays, "");
        } catch (RestClientResponseException exception) {
            String body = exception.getResponseBodyAsString();
            String providerMessage = extractError(body);
            String message = "Jettova itinerary request failed (HTTP " + exception.getStatusCode().value() + ")"
                    + (providerMessage.isBlank() ? "" : ": " + providerMessage);
            log.warn("jettova.itinerary.failed destination={} status={} message={} durationMs={}",
                    destination, exception.getStatusCode().value(), message, elapsedMs(started));
            return JettovaItineraryResponse.failure(message);
        } catch (Exception exception) {
            log.warn("jettova.itinerary.failed destination={} errorType={} message={} durationMs={}",
                    destination, exception.getClass().getSimpleName(), exception.getMessage(), elapsedMs(started));
            return JettovaItineraryResponse.failure("Jettova itinerary request failed: "
                    + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
        }
    }

    private List<String> buildVibes(String style, boolean food, boolean local, boolean family) {
        List<String> vibes = new ArrayList<>();
        if (style != null && !style.isBlank()) {
            String normalized = style.toLowerCase();
            if (normalized.contains("luxury")) vibes.add("luxury");
            else if (normalized.contains("budget")) vibes.add("budget");
            else if (normalized.contains("adventure")) vibes.add("adventure");
            else if (normalized.contains("relax")) vibes.add("relaxation");
            else if (normalized.contains("culture") || normalized.contains("history")) vibes.add("history");
        }
        if (food) vibes.add("foodie");
        if (local) vibes.add("local");
        if (family) vibes.add("family");
        if (vibes.isEmpty()) vibes.add("balanced");
        return vibes.stream().distinct().limit(6).toList();
    }

    private String mapPace(String style) {
        if (style == null) return "balanced";
        String value = style.toLowerCase();
        if (value.contains("relax")) return "relaxed";
        if (value.contains("packed") || value.contains("adventure")) return "packed";
        return "balanced";
    }

    private List<Map<String, Object>> mapDays(JsonNode root) {
        JsonNode days = root.path("days");
        if (!days.isArray()) days = root.path("itinerary");
        if (!days.isArray()) days = root.path("data").path("days");
        if (!days.isArray()) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        int fallbackDay = 1;
        for (JsonNode day : days) {
            int dayNumber = day.path("day").asInt(day.path("day_number").asInt(fallbackDay));
            String title = firstText(day, "title", "name", "theme", "headline");
            List<Map<String, Object>> activities = mapActivities(day);
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("day", dayNumber);
            mapped.put("title", title.isBlank() ? "Explore" : title);
            mapped.put("summary", firstText(day, "summary", "description", "overview"));
            mapped.put("estimatedCost", firstNumberOrString(day, "estimated_cost", "estimated_cost_usd", "cost", "total_cost"));
            mapped.put("currency", firstText(day, "currency", "cost_currency"));
            mapped.put("activities", activities);
            result.add(mapped);
            fallbackDay++;
        }
        return result;
    }

    private List<Map<String, Object>> mapActivities(JsonNode day) {
        JsonNode activities = day.path("activities");
        if (!activities.isArray()) activities = day.path("items");
        if (!activities.isArray()) activities = day.path("stops");
        if (!activities.isArray()) activities = day.path("schedule");
        if (!activities.isArray()) activities = day.path("slots");
        if (!activities.isArray()) return List.of();

        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode activity : activities) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            JsonNode place = firstObject(activity, "place", "venue", "restaurant", "location");
            String name = firstText(activity, "name", "title", "place_name", "venue", "restaurant");
            if (name.isBlank()) name = firstText(place, "name", "title", "display_name");
            if (name.isBlank() && activity.isTextual()) name = activity.asString();
            if (name.isBlank()) continue;
            mapped.put("name", name);
            mapped.put("type", firstText(activity, "type", "category", "kind", "activity_type"));
            mapped.put("description", firstText(activity, "description", "details", "notes", "summary")
                    .isBlank() ? firstText(place, "description", "details", "summary") : firstText(activity, "description", "details", "notes", "summary"));
            mapped.put("location", firstText(activity, "address", "location", "area", "city")
                    .isBlank() ? firstText(place, "address", "location", "area", "city") : firstText(activity, "address", "location", "area", "city"));
            mapped.put("duration", firstText(activity, "duration", "duration_text", "durationText"));
            mapped.put("estimatedCost", firstNumberOrString(activity, "estimated_cost", "estimated_cost_usd", "cost", "price", "estimatedPrice"));
            mapped.put("currency", firstText(activity, "currency", "cost_currency", "price_currency"));
            mapped.put("bookingUrl", firstText(activity, "booking_url", "bookingUrl", "url", "link")
                    .isBlank() ? firstText(place, "booking_url", "bookingUrl", "url", "link") : firstText(activity, "booking_url", "bookingUrl", "url", "link"));
            mapped.put("imageUrl", firstText(activity, "image_url", "imageUrl", "image", "photo_url")
                    .isBlank() ? firstText(place, "image_url", "imageUrl", "image", "photo_url") : firstText(activity, "image_url", "imageUrl", "image", "photo_url"));
            mapped.put("indoorOutdoor", firstText(activity, "indoor_outdoor", "indoorOutdoor", "setting"));
            mapped.put("familyFriendly", firstBoolean(activity, "family_friendly", "familyFriendly"));
            mapped.put("foodExperience", firstBoolean(activity, "food_experience", "foodExperience", "is_food"));
            mapped.put("localExperience", firstBoolean(activity, "local_experience", "localExperience", "is_local"));
            result.add(mapped);
        }
        return result;
    }

    private JsonNode firstObject(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) return node;
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isObject()) return value;
        }
        return node;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asString().isBlank()) return value.asString();
        }
        return "";
    }

    private Object firstNumberOrString(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) return value.doubleValue();
            if (value.isTextual() && !value.asString().isBlank()) return value.asString();
        }
        return "";
    }

    private boolean firstBoolean(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isBoolean()) return value.asBoolean();
        }
        return false;
    }

    private String extractError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isTextual()) return error.asString();
            if (error.isObject()) {
                String code = error.path("code").asString("");
                String message = error.path("message").asString("");
                if (!code.isBlank() && !message.isBlank()) return code + ": " + message;
                if (!message.isBlank()) return message;
                if (!code.isBlank()) return code;
            }
            return root.path("message").asString("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public record JettovaItineraryResponse(boolean success, String provider, String destination,
                                           String summary, List<Map<String, Object>> days, String message) {
        public static JettovaItineraryResponse failure(String message) {
            return new JettovaItineraryResponse(false, "Jettova", "", "", List.of(), message);
        }
    }
}
