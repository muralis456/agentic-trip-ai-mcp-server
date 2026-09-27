package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.AirportResult;
import com.example.travel.mcp.dto.HotelResult;
import com.example.travel.mcp.dto.ResearchHit;
import com.example.travel.mcp.dto.ResearchResult;
import com.example.travel.mcp.dto.SearchHotelsResponse;
import com.example.travel.mcp.dto.WeatherResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TravelMcpServices {

    private static final Logger log = LoggerFactory.getLogger(TravelMcpServices.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String tavilyUrl;
    private final String tavilyApiKey;
    private final String geocodeUrl;
    private final String currentWeatherUrl;
    private final String forecastWeatherUrl;
    private final String openWeatherApiKey;
    private final HotelSearchOrchestrator hotelSearchOrchestrator;
    private final JettovaItineraryProvider jettovaItineraryProvider;

    public TravelMcpServices(RestClient.Builder builder,
                             ObjectMapper objectMapper,
                             @Value("${travel.tavily.api-url:https://api.tavily.com/search}") String tavilyUrl,
                             @Value("${travel.tavily.api-key:}") String tavilyApiKey,
                             @Value("${travel.weather.geocode-url:https://api.openweathermap.org/geo/1.0/direct}") String geocodeUrl,
                             @Value("${travel.weather.current-url:https://api.openweathermap.org/data/2.5/weather}") String currentWeatherUrl,
                             @Value("${travel.weather.forecast-url:https://api.openweathermap.org/data/2.5/forecast}") String forecastWeatherUrl,
                             @Value("${travel.weather.api-key:}") String openWeatherApiKey,
                             HotelSearchOrchestrator hotelSearchOrchestrator,
                             JettovaItineraryProvider jettovaItineraryProvider) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.tavilyUrl = tavilyUrl;
        this.tavilyApiKey = tavilyApiKey;
        this.geocodeUrl = geocodeUrl;
        this.currentWeatherUrl = currentWeatherUrl;
        this.forecastWeatherUrl = forecastWeatherUrl;
        this.openWeatherApiKey = openWeatherApiKey;
        this.hotelSearchOrchestrator = hotelSearchOrchestrator;
        this.jettovaItineraryProvider = jettovaItineraryProvider;
    }

    public SearchHotelsResponse searchHotels(String destination, String travelStyle, boolean cheaper,
                                             LocalDate checkIn, LocalDate checkOut, int adults, int children,
                                             java.math.BigDecimal maxPricePerNight) {
        HotelSearchRequest request = new HotelSearchRequest(
                destination,
                travelStyle == null || travelStyle.isBlank() ? "balanced" : travelStyle,
                cheaper,
                checkIn,
                checkOut,
                Math.max(1, adults),
                Math.max(0, children),
                maxPricePerNight);
        return hotelSearchOrchestrator.search(request);
    }

    public JettovaItineraryProvider.JettovaItineraryResponse generateItinerary(
            String destination, int days, LocalDate startDate, LocalDate endDate, int adults,
            String travelStyle, boolean foodExperience, boolean localExperience, boolean familyFriendly,
            String budgetLabel) {
        return jettovaItineraryProvider.generate(destination, days, startDate, endDate, adults,
                travelStyle, foodExperience, localExperience, familyFriendly, budgetLabel);
    }

    public SearchHotelsResponse searchHotels(String destination, String travelStyle, boolean cheaper) {
        LocalDate checkIn = LocalDate.now().plusDays(1);
        return searchHotels(destination, travelStyle, cheaper, checkIn, checkIn.plusDays(1), 2, 0, null);
    }

    public WeatherResult weather(String destination, LocalDate start, LocalDate end) {
        LocalDate from = start == null ? LocalDate.now() : start;
        LocalDate to = end == null ? from.plusDays(5) : end;
        try {
            if (openWeatherApiKey == null || openWeatherApiKey.isBlank()) {
                return WeatherResult.failure(destination, "OpenWeather API key is not configured.");
            }

            String geoUri = UriComponentsBuilder.fromUriString(geocodeUrl)
                    .queryParam("q", destination)
                    .queryParam("limit", 1)
                    .queryParam("appid", openWeatherApiKey)
                    .build().toUriString();
            JsonNode geo = objectMapper.readTree(restClient.get().uri(geoUri).retrieve().body(String.class));
            if (!geo.isArray() || geo.isEmpty()) {
                return WeatherResult.failure(destination, "Weather location could not be resolved.");
            }

            JsonNode place = geo.get(0);
            double lat = place.path("lat").asDouble();
            double lon = place.path("lon").asDouble();
            String resolvedLocation = place.path("name").asString(destination);

            String currentUri = UriComponentsBuilder.fromUriString(currentWeatherUrl)
                    .queryParam("lat", lat)
                    .queryParam("lon", lon)
                    .queryParam("units", "metric")
                    .queryParam("lang", "en")
                    .queryParam("appid", openWeatherApiKey)
                    .build().toUriString();
            JsonNode current = objectMapper.readTree(restClient.get().uri(currentUri).retrieve().body(String.class));

            String forecastUri = UriComponentsBuilder.fromUriString(forecastWeatherUrl)
                    .queryParam("lat", lat)
                    .queryParam("lon", lon)
                    .queryParam("units", "metric")
                    .queryParam("lang", "en")
                    .queryParam("appid", openWeatherApiKey)
                    .build().toUriString();
            JsonNode forecastRoot = objectMapper.readTree(restClient.get().uri(forecastUri).retrieve().body(String.class));

            JsonNode city = forecastRoot.path("city");
            String timezone = timezoneLabel(city.path("timezone").asInt(0));
            WeatherResult.CurrentWeather currentWeather = mapCurrent(current, timezone);
            List<WeatherResult.DailyWeather> days = aggregateDailyForecast(forecastRoot, from, to);

            boolean rain = currentRain(currentWeather)
                    || days.stream().anyMatch(d -> d.rainProbability() != null && d.rainProbability() >= 40);
            String summary = buildSummary(currentWeather, days, rain);
            return new WeatherResult(true, resolvedLocation, summary, rain, currentWeather, days);
        } catch (RestClientResponseException exception) {
            String responseBody = exception.getResponseBodyAsString();
            String providerMessage = extractProviderMessage(responseBody);
            String reason = "OpenWeather request failed (HTTP " + exception.getStatusCode().value() + ")"
                    + (providerMessage.isBlank() ? "" : ": " + providerMessage);
            log.warn("mcp.provider.weather failed destination={} status={} reason={} response={}",
                    destination, exception.getStatusCode().value(), reason, sanitizeProviderResponse(responseBody));
            return WeatherResult.failure(destination, reason);
        } catch (Exception exception) {
            log.warn("mcp.provider.weather failed destination={} exceptionType={} error={}",
                    destination, exception.getClass().getName(), exception.getMessage(), exception);
            String message = exception.getMessage();
            String reason = message == null || message.isBlank()
                    ? "OpenWeather lookup failed: " + exception.getClass().getSimpleName()
                    : "OpenWeather lookup failed: " + message;
            return WeatherResult.failure(destination, reason);
        }
    }

    private List<WeatherResult.DailyWeather> aggregateDailyForecast(JsonNode forecastRoot, LocalDate from, LocalDate to) {
        JsonNode list = forecastRoot.path("list");
        if (!list.isArray()) {
            return List.of();
        }

        Map<LocalDate, List<JsonNode>> grouped = new java.util.TreeMap<>();
        for (JsonNode item : list) {
            String dtText = item.path("dt_txt").asString("");
            LocalDate date = parseForecastDate(dtText);
            if (date == null || date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            grouped.computeIfAbsent(date, ignored -> new ArrayList<>()).add(item);
        }

        List<WeatherResult.DailyWeather> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<JsonNode>> entry : grouped.entrySet()) {
            LocalDate date = entry.getKey();
            List<JsonNode> entries = entry.getValue();
            Double high = null;
            Double low = null;
            Integer maxPop = null;
            JsonNode representative = null;
            double representativePop = -1;

            for (JsonNode item : entries) {
                JsonNode main = item.path("main");
                if (main.path("temp_max").isNumber()) {
                    high = high == null ? main.path("temp_max").doubleValue() : Math.max(high, main.path("temp_max").doubleValue());
                }
                if (main.path("temp_min").isNumber()) {
                    low = low == null ? main.path("temp_min").doubleValue() : Math.min(low, main.path("temp_min").doubleValue());
                }
                if (item.path("pop").isNumber()) {
                    int pop = (int) Math.round(item.path("pop").doubleValue() * 100.0);
                    maxPop = maxPop == null ? pop : Math.max(maxPop, pop);
                    if (pop > representativePop) {
                        representativePop = pop;
                        representative = item;
                    }
                }
            }

            if (representative == null && !entries.isEmpty()) {
                representative = entries.get(entries.size() / 2);
            }
            JsonNode weather = firstWeather(representative);
            days.add(new WeatherResult.DailyWeather(
                    date.toString(),
                    weather.path("description").asString(weather.path("main").asString("Forecast")),
                    weather.path("icon").asString(""),
                    high,
                    low,
                    maxPop));
        }
        return days;
    }

    private LocalDate parseForecastDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate();
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private String timezoneLabel(int offsetSeconds) {
        if (offsetSeconds == 0) return "UTC";
        int sign = offsetSeconds >= 0 ? 1 : -1;
        int absolute = Math.abs(offsetSeconds);
        int hours = absolute / 3600;
        int minutes = (absolute % 3600) / 60;
        return String.format(Locale.ROOT, "UTC%s%02d:%02d", sign > 0 ? "+" : "-", hours, minutes);
    }


    private String extractProviderMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("message").asString("");
            if (!message.isBlank()) {
                return message;
            }
            return root.path("error").asString("");
        } catch (Exception ignored) {
            return responseBody.trim();
        }
    }

    private String sanitizeProviderResponse(String responseBody) {
        if (responseBody == null) {
            return "";
        }
        String value = responseBody.replaceAll("[\\r\\n\\t]", " ").trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private JsonNode firstData(JsonNode root) {
        JsonNode data = root.path("data");
        return data.isArray() && !data.isEmpty() ? data.get(0) : root;
    }

    private JsonNode firstWeather(JsonNode node) {
        JsonNode weather = node.path("weather");
        return weather.isArray() && !weather.isEmpty() ? weather.get(0) : objectMapper.createObjectNode();
    }

    private WeatherResult.CurrentWeather mapCurrent(JsonNode current, String timezone) {
        JsonNode main = current.path("main");
        JsonNode weather = firstWeather(current);
        JsonNode wind = current.path("wind");
        JsonNode clouds = current.path("clouds");
        JsonNode rain = current.path("rain");
        JsonNode snow = current.path("snow");
        JsonNode sys = current.path("sys");
        return new WeatherResult.CurrentWeather(
                number(main, "temp"), number(main, "feels_like"), integer(main, "humidity"),
                integer(main, "pressure"), null, null,
                integer(clouds, "all"), integer(current, "visibility"), number(wind, "speed"),
                number(wind, "gust"), integer(wind, "deg"),
                number(rain, "1h"), number(snow, "1h"), longValue(current, "dt"),
                longValue(sys, "sunrise"), longValue(sys, "sunset"), timezone,
                weather.path("main").asString(""), weather.path("description").asString(""), weather.path("icon").asString(""));
    }

    private boolean currentRain(WeatherResult.CurrentWeather current) {
        if (current == null) return false;
        return (current.rain1h() != null && current.rain1h() > 0)
                || (current.snow1h() != null && current.snow1h() > 0)
                || "Rain".equalsIgnoreCase(current.condition())
                || "Drizzle".equalsIgnoreCase(current.condition())
                || "Thunderstorm".equalsIgnoreCase(current.condition());
    }

    private String buildSummary(WeatherResult.CurrentWeather current, List<WeatherResult.DailyWeather> days, boolean rain) {
        String condition = current == null ? "Current conditions unavailable" : current.description();
        if (rain) {
            return condition + ". Rain or precipitation is possible during the travel window; keep weather-flexible plans available.";
        }
        return condition + ". Conditions are currently suitable for outdoor travel, with no high rain probability in the returned outlook.";
    }

    private String localDate(long epochSeconds, String timezone) {
        if (epochSeconds <= 0) return "";
        try {
            return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone)).toLocalDate().toString();
        } catch (Exception ignored) {
            return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.of("UTC")).toLocalDate().toString();
        }
    }

    private Double number(JsonNode node, String field) {
        return node != null && node.has(field) && node.path(field).isNumber() ? node.path(field).doubleValue() : null;
    }

    private Integer integer(JsonNode node, String field) {
        return node != null && node.has(field) && node.path(field).isNumber() ? node.path(field).intValue() : null;
    }

    private Long longValue(JsonNode node, String field) {
        return node != null && node.has(field) && node.path(field).isNumber() ? node.path(field).longValue() : null;
    }

    public AirportResult resolveAirport(String cityOrCode) {
        if (cityOrCode == null || cityOrCode.isBlank()) {
            return new AirportResult(false, "", "", "", "Airport query is required.");
        }
        String value = cityOrCode.trim();
        if (value.matches("[A-Za-z]{3}")) {
            return new AirportResult(true, value.toUpperCase(Locale.ROOT), value, "", "Airport code accepted.");
        }
        Map<String, String> known = Map.ofEntries(
                Map.entry("bengaluru", "BLR"), Map.entry("bangalore", "BLR"), Map.entry("mumbai", "BOM"),
                Map.entry("delhi", "DEL"), Map.entry("new delhi", "DEL"), Map.entry("chennai", "MAA"),
                Map.entry("hyderabad", "HYD"), Map.entry("kolkata", "CCU"), Map.entry("goa", "GOI"),
                Map.entry("pune", "PNQ"), Map.entry("paris", "CDG"), Map.entry("tokyo", "NRT"),
                Map.entry("london", "LHR"), Map.entry("new york", "JFK"));
        String iata = known.get(value.toLowerCase(Locale.ROOT));
        return iata == null
                ? new AirportResult(false, "", value, "", "No airport found for " + value + ".")
                : new AirportResult(true, iata, value, "", "Airport resolved.");
    }

    public ResearchResult research(String query) {
        if (query == null || query.isBlank()) {
            return ResearchResult.failure("Research query is required.");
        }
        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            return ResearchResult.failure("Travel research provider is not configured.");
        }
        try {
            String body = restClient.post().uri(tavilyUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tavilyApiKey)
                    .body(Map.of("query", query, "max_results", 5))
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            List<ResearchHit> hits = new ArrayList<>();
            for (JsonNode result : root.path("results")) {
                hits.add(new ResearchHit(result.path("title").asString(""),
                        result.path("content").asString(""), result.path("url").asString(""),
                        result.path("score").asDouble(0)));
            }
            return ResearchResult.success(hits, "Travel research completed.");
        } catch (Exception exception) {
            log.warn("mcp.provider.tavily failed queryLength={} error={}", query.length(), exception.getMessage());
            return ResearchResult.failure("Travel research provider is unavailable.");
        }
    }
}
