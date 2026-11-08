package com.momentory.weather.domain;

import java.time.Instant;

public record CurrentWeather(WeatherCondition condition, String symbolCode, String label,
        Integer temperatureC, Instant forecastTime, Instant updatedAt) {
}
