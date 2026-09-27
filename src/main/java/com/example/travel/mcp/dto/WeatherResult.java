package com.example.travel.mcp.dto;

import java.util.List;

public record WeatherResult(
        boolean success,
        String location,
        String summary,
        boolean rainLikely,
        CurrentWeather current,
        List<DailyWeather> days) {

    public record CurrentWeather(
            Double temperature,
            Double feelsLike,
            Integer humidity,
            Integer pressure,
            Double dewPoint,
            Double uvIndex,
            Integer clouds,
            Integer visibilityMeters,
            Double windSpeed,
            Double windGust,
            Integer windDeg,
            Double rain1h,
            Double snow1h,
            Long observedAt,
            Long sunrise,
            Long sunset,
            String timezone,
            String condition,
            String description,
            String icon) {
    }

    public record DailyWeather(
            String date,
            String condition,
            String icon,
            Double high,
            Double low,
            Integer rainProbability) {
    }

    public static WeatherResult failure(String location, String summary) {
        return new WeatherResult(false, location, summary, false, null, List.of());
    }
}
