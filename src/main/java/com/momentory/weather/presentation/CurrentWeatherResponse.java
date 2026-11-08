package com.momentory.weather.presentation;

import com.momentory.weather.domain.CurrentWeather;
import com.momentory.weather.domain.WeatherCondition;
import java.time.Instant;

public record CurrentWeatherResponse(WeatherCondition condition, String symbolCode, String label,
        Integer temperatureC, Instant forecastTime, Instant updatedAt,
        String source, String sourceUrl, String licenseUrl) {
    public static CurrentWeatherResponse from(CurrentWeather weather) {
        return new CurrentWeatherResponse(weather.condition(), weather.symbolCode(), weather.label(),
                weather.temperatureC(), weather.forecastTime(), weather.updatedAt(),
                "MET Norway", "https://api.met.no/", "https://creativecommons.org/licenses/by/4.0/");
    }
}
